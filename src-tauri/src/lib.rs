//! Kaisha の native host。画面・状態・判定はすべて WebView の中（kaisha.app）にあり、
//! ここはウィンドウを開くだけの薄い adapter。ファイル・ネットワーク・shell の権限は持たない。

#[tauri::command]
fn awai_host_info() -> serde_json::Value {
    serde_json::json!({"schema": "awai.shell.host.v1", "app": "network.itonami.kaisha"})
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .runtime(tauri_runtime_wry::Wry::default())
        .invoke_handler(tauri::generate_handler![awai_host_info])
        .run(tauri::generate_context!())
        .expect("Kaisha initialization failed");
}
