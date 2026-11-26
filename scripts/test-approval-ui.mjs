// Headless automated UI test for Kaisha approval card:
// 1. Verifies dialog rendering (role="alertdialog")
// 2. Verifies focus / IME composition retention during continuous evidence input
// 3. Verifies dynamic preview & confirm button activation in-place without DOM replacement
// 4. Verifies Cancel / Escape preserves unmodified state in storage
// 5. Verifies Confirm saves evidence and confirmation date to storage and audit log

class MockNode {
  constructor(nodeType, nodeName) {
    this.nodeType = nodeType;
    this.nodeName = nodeName;
    this.childNodes = [];
    this.parentNode = null;
  }
  appendChild(child) {
    if (child.nodeType === 11) { // DocumentFragment
      const children = [...child.childNodes];
      for (const c of children) this.appendChild(c);
      child.childNodes = [];
      return child;
    }
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  }
  removeChild(child) {
    const idx = this.childNodes.indexOf(child);
    if (idx !== -1) {
      this.childNodes.splice(idx, 1);
      child.parentNode = null;
    }
    return child;
  }
  replaceChildren(...newChildren) {
    while (this.childNodes.length > 0) {
      this.removeChild(this.childNodes[0]);
    }
    for (const c of newChildren) {
      if (c) this.appendChild(c);
    }
  }
  get textContent() {
    return this.childNodes.map(c => c.textContent || '').join('');
  }
  set textContent(val) {
    this.replaceChildren(new MockText(val));
  }
}

class MockText extends MockNode {
  constructor(text) {
    super(3, '#text');
    this.nodeValue = String(text);
  }
  get textContent() {
    return this.nodeValue;
  }
  set textContent(val) {
    this.nodeValue = String(val);
  }
}

class MockElement extends MockNode {
  constructor(tagName) {
    super(1, tagName.toUpperCase());
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.listeners = new Map();
    this.className = '';
    this._value = '';
    this.disabled = false;
    this.checked = false;
  }
  setAttribute(k, v) {
    this.attributes.set(k.toLowerCase(), String(v));
    if (k.toLowerCase() === 'class') this.className = String(v);
    if (k.toLowerCase() === 'disabled') this.disabled = true;
  }
  removeAttribute(k) {
    this.attributes.delete(k.toLowerCase());
    if (k.toLowerCase() === 'disabled') this.disabled = false;
  }
  getAttribute(k) {
    return this.attributes.get(k.toLowerCase()) ?? null;
  }
  hasAttribute(k) {
    return this.attributes.has(k.toLowerCase());
  }
  get value() {
    return this._value;
  }
  set value(v) {
    this._value = String(v);
  }
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(fn);
  }
  dispatchEvent(event) {
    event.target = event.target || this;
    event.currentTarget = this;
    const handlers = this.listeners.get(event.type) || [];
    for (const fn of handlers) fn.call(this, event);
    if (this.parentNode && event.bubbles === true) {
      this.parentNode.dispatchEvent(event);
    }
  }
  focus() {
    global.document.activeElement = this;
  }
  closest(selector) {
    let curr = this;
    while (curr && curr.nodeType === 1) {
      if (curr.matches(selector)) return curr;
      curr = curr.parentNode;
    }
    return null;
  }
  matches(selector) {
    let s = selector;
    let tag = null;
    const bracketIdx = s.indexOf('[');
    if (bracketIdx > 0) {
      tag = s.slice(0, bracketIdx);
      s = s.slice(bracketIdx);
    }
    if (tag && this.tagName.toLowerCase() !== tag.toLowerCase()) return false;
    if (s.startsWith('.')) {
      const cls = s.slice(1);
      return (this.className || '').split(/\s+/).includes(cls);
    }
    if (s.startsWith('[') && s.endsWith(']')) {
      const inner = s.slice(1, -1);
      if (inner.includes('=')) {
        const [k, v] = inner.split('=');
        const cleanV = v.replace(/^["']|["']$/g, '');
        return this.getAttribute(k) === cleanV;
      }
      return this.hasAttribute(inner);
    }
    return this.tagName.toLowerCase() === s.toLowerCase();
  }
  querySelector(selector) {
    const all = this.querySelectorAll(selector);
    return all.length > 0 ? all[0] : null;
  }
  querySelectorAll(selector) {
    const results = [];
    const walk = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 1) {
          if (child.matches(selector)) results.push(child);
          walk(child);
        }
      }
    };
    walk(this);
    return results;
  }
}

class MockDocument extends MockNode {
  constructor() {
    super(9, '#document');
    this.documentElement = new MockElement('html');
    this.body = new MockElement('body');
    this.documentElement.appendChild(this.body);
    this.appendChild(this.documentElement);
    this.activeElement = this.body;
  }
  createElement(tag) {
    return new MockElement(tag);
  }
  createTextNode(text) {
    return new MockText(text);
  }
  createDocumentFragment() {
    return new MockNode(11, '#document-fragment');
  }
  getElementById(id) {
    return this.querySelectorAll('[id]').find(el => el.getAttribute('id') === id) ?? null;
  }
  querySelector(sel) {
    return this.documentElement.querySelector(sel);
  }
  querySelectorAll(sel) {
    return this.documentElement.querySelectorAll(sel);
  }
}

// Setup environment
const storage = new Map();
const mockLocalStorage = {
  getItem: (k) => {
    const val = storage.get(k) || null;
    return val;
  },
  setItem: (k, v) => {
    console.log('setItem called:', k);
    storage.set(k, String(v));
  },
  removeItem: (k) => {
    console.log('removeItem called:', k);
    storage.delete(k);
  }
};
Object.defineProperty(globalThis, 'localStorage', { value: mockLocalStorage, configurable: true, writable: true });
global.window = {
  localStorage: mockLocalStorage,
  scrollY: 0,
  scrollTo: () => {},
  addEventListener: () => {}
};
Object.defineProperty(globalThis, 'navigator', { value: { language: 'ja-JP' }, configurable: true });
global.self = global;
global.document = new MockDocument();
const appRoot = global.document.createElement('div');
appRoot.setAttribute('id', 'app');
global.document.body.appendChild(appRoot);

async function run() {
  console.log('1. Loading Kaisha app...');
  await import('../dist/main.js');
  
  console.log('2. Finding representative officer verify button on Home screen...');
  const verifyBtn = appRoot.querySelectorAll('button').find(b => b.textContent.includes('登記事項で確認した'));
  if (!verifyBtn) {
    throw new Error('Verify button not found on home screen');
  }

  console.log('3. Opening approval dialog for officer.verify...');
  const storedBeforeCancel = JSON.stringify([...storage]);
  verifyBtn.dispatchEvent({ type: 'click' });

  // Verify dialog presence and attributes
  const dialog = appRoot.querySelector('.dialog');
  if (!dialog) throw new Error('Dialog not found');
  if (dialog.getAttribute('role') !== 'alertdialog') throw new Error('Dialog does not have role="alertdialog"');
  if (dialog.getAttribute('aria-modal') !== 'true') throw new Error('Dialog does not have aria-modal="true"');
  
  const title = dialog.querySelector('h2');
  if (!title || !title.textContent.includes('代表権の確認')) {
    throw new Error(`Unexpected dialog title: ${title?.textContent}`);
  }
  console.log('   Dialog opened with title:', title.textContent);

  // Verify initial dialog content
  const textarea = dialog.querySelector('textarea[name="evidence"]');
  if (!textarea) throw new Error('Evidence textarea not found');
  const confirmBtn = dialog.querySelector('[data-approval-confirm]');
  if (!confirmBtn) throw new Error('Confirm button not found');
  if (confirmBtn.disabled !== true) throw new Error('Confirm button should initially be disabled');

  const reasonEl = dialog.querySelector('[data-approval-reason]');
  if (!reasonEl || !reasonEl.textContent.includes('証跡を入力してください')) {
    throw new Error(`Initial reason should guide user to enter evidence, got: ${reasonEl?.textContent}`);
  }
  console.log('   Initial validation state: confirm button disabled, evidence required note present.');

  // Focus textarea
  textarea.focus();
  if (global.document.activeElement !== textarea) throw new Error('Textarea was not focused');

  console.log('4. Testing continuous input and focus/IME composition retention...');
  const initialTextareaNode = textarea;
  const inputChars = ['登', '記', '事', '項', '証', '明', '書', ' ', '確', '認', '済', 'み'];
  let currentVal = '';

  for (let i = 0; i < inputChars.length; i++) {
    currentVal += inputChars[i];
    textarea.value = currentVal;
    // Dispatch input event simulating typing
    textarea.dispatchEvent({ type: 'input' });

    // CRITICAL: Textarea node MUST remain the exact same object in DOM and remain activeElement
    if (global.document.activeElement !== initialTextareaNode) {
      throw new Error(`Focus lost at character ${i} ('${inputChars[i]}')! activeElement changed`);
    }
    const currentTextareaInDOM = dialog.querySelector('textarea[name="evidence"]');
    if (currentTextareaInDOM !== initialTextareaNode) {
      throw new Error(`Textarea DOM node was replaced at character ${i}! IME composition would be destroyed`);
    }

    // Confirm button must become enabled once text is non-empty
    if (confirmBtn.disabled !== false) {
      throw new Error(`Confirm button should be enabled after input, but disabled=${confirmBtn.disabled}`);
    }
  }
  console.log(`   Typed ${inputChars.length} characters continuously. Focus was never lost; textarea DOM node remained stable.`);

  const effectsEl = dialog.querySelector('[data-approval-effects]');
  if (!effectsEl || !effectsEl.textContent.includes('登記事項証明書 確認済み')) {
    throw new Error(`Effects list did not update with evidence: ${effectsEl?.textContent}`);
  }
  if (reasonEl.textContent.trim() !== '') {
    throw new Error(`Reason error should be cleared when evidence is present: ${reasonEl.textContent}`);
  }
  console.log('   In-place dynamic updates verified: effects reflect evidence, error message cleared.');

  console.log('5. Testing Escape and Cancel: focus restoration and unchanged storage...');
  function assertCancelled(opener, method) {
    if (appRoot.querySelector('.dialog')) throw new Error(`Dialog did not close on ${method}`);
    const restored = global.document.getElementById(opener.getAttribute('id'));
    if (!restored || restored === opener || global.document.activeElement !== restored) {
      throw new Error(`${method} did not restore focus to the rebuilt opener`);
    }
    if (JSON.stringify([...storage]) !== storedBeforeCancel) {
      throw new Error(`${method} modified storage`);
    }
    return restored;
  }

  global.document.activeElement.dispatchEvent({ type: 'keydown', key: 'Escape', bubbles: true });
  const restoredAfterEscape = assertCancelled(verifyBtn, 'Escape');
  restoredAfterEscape.dispatchEvent({ type: 'click' });

  const cancelDialog = appRoot.querySelector('.dialog');
  const cancelEvidence = cancelDialog.querySelector('textarea[name="evidence"]');
  cancelEvidence.value = '取消する証跡';
  cancelEvidence.dispatchEvent({ type: 'input' });
  cancelDialog.querySelectorAll('button').find(b => b.textContent === 'やめる').dispatchEvent({ type: 'click' });
  const restoredAfterCancel = assertCancelled(restoredAfterEscape, 'Cancel');

  console.log('6. Testing Confirm flow: reopen, enter evidence, and confirm...');
  restoredAfterCancel.dispatchEvent({ type: 'click' });

  const dialog2 = appRoot.querySelector('.dialog');
  const textarea2 = dialog2.querySelector('textarea[name="evidence"]');
  textarea2.value = '法務局取得 履歴事項全部証明書 2026-11-20';
  textarea2.dispatchEvent({ type: 'input' });

  const confirmBtn2 = dialog2.querySelector('[data-approval-confirm]');
  if (confirmBtn2.disabled) throw new Error('Confirm button should be enabled');
  confirmBtn2.dispatchEvent({ type: 'click' });

  // Dialog should close after successful action
  if (appRoot.querySelector('.dialog')) {
    throw new Error('Dialog should close after confirmation');
  }

  // Storage MUST now contain the evidence and audit log
  const updatedStorage = storage.get('itonami.kaisha.state.v2');
  if (!updatedStorage.includes('法務局取得 履歴事項全部証明書 2026-11-20')) {
    throw new Error('Confirmed evidence was not saved to storage!');
  }
  if (!updatedStorage.includes(':officer/authority-verified-on')) {
    throw new Error('Confirmed verification date was not saved to storage!');
  }
  console.log('   Confirmation succeeded. Evidence and verification date persisted to state and audit.');

  console.log('7. Navigating to Settings to test reset approval card flow...');
  // Find settings button in header (app-bar)
  const settingsBtn = appRoot.querySelectorAll('button').find(b => b.getAttribute('aria-label') === '設定を開く');
  if (!settingsBtn) throw new Error('Settings button not found in app-bar');
  settingsBtn.dispatchEvent({ type: 'click' });

  // In settings, find the reset-demo-btn
  const resetBtn = global.document.getElementById('reset-demo-btn');
  if (!resetBtn) throw new Error('reset-demo-btn not found in settings');
  console.log('   Found reset button in settings screen.');

  console.log('8. Opening approval dialog for :data/reset-demo...');
  const storedBeforeReset = JSON.stringify([...storage]);
  resetBtn.dispatchEvent({ type: 'click' });

  const resetDialog = appRoot.querySelector('.dialog');
  if (!resetDialog) throw new Error('Reset approval dialog not found');
  if (resetDialog.getAttribute('role') !== 'alertdialog') throw new Error('Reset dialog does not have role="alertdialog"');
  if (resetDialog.getAttribute('aria-modal') !== 'true') throw new Error('Reset dialog does not have aria-modal="true"');

  const resetTitle = resetDialog.querySelector('h2');
  if (!resetTitle || !resetTitle.textContent.includes('架空データ')) {
    throw new Error(`Unexpected reset dialog title: ${resetTitle?.textContent}`);
  }
  console.log('   Reset dialog opened with title:', resetTitle.textContent);

  // Verify chips and effects
  const chips = resetDialog.querySelectorAll('.chip');
  const chipTexts = chips.map(c => c.textContent).join(' ');
  if (!chipTexts.includes('取り消せません') || !chipTexts.includes('人による承認が必要です')) {
    throw new Error(`Chips do not contain required warnings: ${chipTexts}`);
  }

  const resetEffectsEl = resetDialog.querySelector('[data-approval-effects]');
  if (!resetEffectsEl) throw new Error('Reset effects list not found');
  const effectsText = resetEffectsEl.textContent;
  if (!effectsText.includes('架空商事株式会社')) {
    throw new Error(`Effects does not include restore target: ${effectsText}`);
  }
  if (!effectsText.includes('失われる件数') || !effectsText.includes('会社 1件')) {
    throw new Error(`Effects does not include lost items dry-run counts: ${effectsText}`);
  }
  if (!effectsText.includes('取り消せません')) {
    throw new Error(`Effects does not include irreversible warning: ${effectsText}`);
  }
  console.log('   Dry-run counts and irreversible warnings verified in effects.');

  const resetConfirmBtn = resetDialog.querySelector('[data-approval-confirm]');
  if (!resetConfirmBtn) throw new Error('Confirm button not found in reset dialog');
  if (resetConfirmBtn.disabled !== false) throw new Error('Confirm button should be enabled for reset');
  if (resetConfirmBtn.getAttribute('data-destructive') !== 'true') {
    throw new Error('Confirm button should have data-destructive="true"');
  }

  console.log('9. Testing Escape and Cancel on reset approval dialog...');
  function assertResetCancelled(opener, method) {
    if (appRoot.querySelector('.dialog')) throw new Error(`Dialog did not close on ${method}`);
    const restored = global.document.getElementById(opener.getAttribute('id'));
    if (!restored || restored === opener || global.document.activeElement !== restored) {
      throw new Error(`${method} did not restore focus to the rebuilt opener`);
    }
    if (JSON.stringify([...storage]) !== storedBeforeReset) {
      throw new Error(`${method} modified storage`);
    }
    return restored;
  }

  global.document.activeElement.dispatchEvent({ type: 'keydown', key: 'Escape', bubbles: true });
  const restoredResetAfterEscape = assertResetCancelled(resetBtn, 'Escape');

  restoredResetAfterEscape.dispatchEvent({ type: 'click' });
  const cancelResetDialog = appRoot.querySelector('.dialog');
  cancelResetDialog.querySelectorAll('button').find(b => b.textContent === 'やめる').dispatchEvent({ type: 'click' });
  const restoredResetAfterCancel = assertResetCancelled(restoredResetAfterEscape, 'Cancel');
  console.log('   Escape and Cancel properly preserved storage and restored focus to reset-demo-btn.');

  console.log('10. Testing Confirm flow for reset...');
  restoredResetAfterCancel.dispatchEvent({ type: 'click' });
  const dialogToConfirm = appRoot.querySelector('.dialog');
  const finalConfirmBtn = dialogToConfirm.querySelector('[data-approval-confirm]');
  finalConfirmBtn.dispatchEvent({ type: 'click' });

  if (appRoot.querySelector('.dialog')) {
    throw new Error('Dialog should be closed after confirmation');
  }

  // Storage MUST now be reset to sample data, with officer verify wiped, and exactly 1 audit entry for :data/reset-demo
  const resetStorage = storage.get('itonami.kaisha.state.v2');
  if (resetStorage.includes('法務局取得 履歴事項全部証明書 2026-11-20')) {
    throw new Error('Storage should have wiped verified officer evidence on reset!');
  }
  if (!resetStorage.includes(':data/reset-demo')) {
    throw new Error('Storage does not contain :data/reset-demo audit entry!');
  }
  // Check audit count in storage
  const auditMatch = resetStorage.match(/:audit\s*\[(.*?)\]/s);
  if (!auditMatch) throw new Error('Could not find :audit in storage');
  const auditEntries = auditMatch[1].trim();
  const entryCount = (auditEntries.match(/\{/g) || []).length;
  if (entryCount !== 1) {
    throw new Error(`Expected exactly 1 audit entry after reset, found ${entryCount}: ${auditEntries}`);
  }
  console.log('   Reset confirmation verified: state restored to fixture, audit has exactly 1 entry for :data/reset-demo.');

  console.log('\nALL UI TESTS PASSED SUCCESSFULLY! (Focus retention, IME safety, Escape, Cancel, Confirm, and Reset verified)');
}

run().catch(err => {
  console.error('\nUI TEST FAILED:', err);
  process.exit(1);
});
