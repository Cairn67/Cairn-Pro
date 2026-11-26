// SPDX-License-Identifier: GPL-3.0-only
// UI strings only. No device logic depends on the selected language.

// Bilingual strings for the interactive builder.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Lang { ZhCn, ZhTw, En }

pub struct Strings {
    pub title: &'static str,
    pub choose_language: &'static str,
    pub lang_zhcn: &'static str,
    pub lang_zhtw: &'static str,
    pub lang_en: &'static str,
    pub need_tty: &'static str,
    pub ask_image: &'static str,
    pub image_empty: &'static str,
    pub scanning: &'static str,
    pub identified: &'static str,
    pub unknown_image: &'static str,
    pub not_android: &'static str,
    pub header_unsupported: &'static str,
    pub kernel_not_arm64: &'static str,
    pub no_symbols: &'static str,
    pub symbols_found: &'static str,
    pub resolving_hooks: &'static str,
    pub hook_ok: &'static str,
    pub hook_mismatch: &'static str,
    pub hook_missing: &'static str,
    pub refuse_incomplete: &'static str,
    pub refuse_experimental: &'static str,
    pub refuse_profile_targets: &'static str,
    pub building: &'static str,
    pub build_done: &'static str,
    pub output_dir: &'static str,
    pub press_enter: &'static str,
    pub cancelled: &'static str,
    pub warn_avb: &'static str,
    pub ask_custom_key: &'static str,
    pub custom_key_prompt: &'static str,
    pub custom_key_invalid: &'static str,
    pub summary_header: &'static str,
    pub dynamic_title: &'static str,
}

pub fn strings(lang: Lang) -> &'static Strings {
    match lang {
        Lang::ZhCn => &ZH_CN,
        Lang::ZhTw => &ZH_TW,
        Lang::En => &EN,
    }
}

static ZH_CN: Strings = Strings {
    title: "Cairn Pro 1.0.1 内核修补器",
    choose_language: "请选择输出语言：",
    lang_zhcn: "1. 简体中文",
    lang_zhtw: "2. 繁體中文",
    lang_en: "3. English",
    need_tty: "需要交互式终端才能运行。",
    ask_image: "请输入内核/引导镜像路径（留空退出）：",
    image_empty: "路径不能为空，未写入任何文件。",
    scanning: "正在识别镜像…",
    identified: "已识别设备方案：",
    unknown_image: "不按机型识别；全部站点与偏移从本镜像自身推导。",
    not_android: "不是有效的 Android boot 镜像。",
    header_unsupported: "仅支持 boot header v3/v4。",
    kernel_not_arm64: "内核不是未压缩的 ARM64 Image，当前不支持 gzip/LZ4。",
    no_symbols: "未能从镜像中自动发现符号表。",
    symbols_found: "已自动发现符号表，符号数量：",
    resolving_hooks: "正在按符号定位补丁点…",
    hook_ok: "一致",
    hook_mismatch: "指令不匹配",
    hook_missing: "符号缺失",
    refuse_incomplete: "补丁点未能全部验证，为避免损伤内核，已拒绝生成镜像。",
    refuse_experimental: "该方案仍是实验状态，未通过真机验证，已拒绝生成。",
    refuse_profile_targets: "该方案仍有未解析的内核函数地址，已拒绝生成。",
    building: "正在生成镜像…",
    build_done: "生成完成。",
    output_dir: "输出位置：",
    press_enter: "按回车退出。",
    cancelled: "已取消，未写入任何文件。",
    warn_avb: "警告：AVB 未重新签名，刷写前请自行承担风险。",
    ask_custom_key: "是否自定义授权密钥？[y/N]：",
    custom_key_prompt: "请输入 32 位十六进制密钥：",
    custom_key_invalid: "密钥格式无效。",
    summary_header: "补丁摘要",
    dynamic_title: "动态修补",
};

static ZH_TW: Strings = Strings {
    title: "Cairn Pro 1.0.1 核心修補器",
    choose_language: "請選擇輸出語言：",
    lang_zhcn: "1. 简体中文",
    lang_zhtw: "2. 繁體中文",
    lang_en: "3. English",
    need_tty: "需要互動式終端才能執行。",
    ask_image: "請輸入核心／開機镜像路徑（留空離開）：",
    image_empty: "路徑不能為空，未寫入任何檔案。",
    scanning: "正在辨識镜像…",
    identified: "已辨識裝置方案：",
    unknown_image: "不按機型辨識；全部站點與偏移從本鏡像自身推導。",
    not_android: "不是有效的 Android boot 镜像。",
    header_unsupported: "僅支援 boot header v3/v4。",
    kernel_not_arm64: "核心不是未壓縮的 ARM64 Image，目前不支援 gzip/LZ4。",
    no_symbols: "無法從镜像中自動發現符號表。",
    symbols_found: "已自動發現符號表，符號數量：",
    resolving_hooks: "正在依符號定位修補點…",
    hook_ok: "一致",
    hook_mismatch: "指令不符",
    hook_missing: "符號缺失",
    refuse_incomplete: "修補點未能全部驗證，為避免損傷核心，已拒絕產生镜像。",
    refuse_experimental: "此方案仍為實驗狀態，未通過實機驗證，已拒絕產生。",
    refuse_profile_targets: "此方案仍有未解析的核心函式位址，已拒絕產生。",
    building: "正在產生镜像…",
    build_done: "產生完成。",
    output_dir: "輸出路徑：",
    press_enter: "按 Enter 離開。",
    cancelled: "已取消，未寫入任何檔案。",
    warn_avb: "警告：AVB 未重新簽章，刷寫前請自行承擔風險。",
    ask_custom_key: "是否自訂授權金鑰？[y/N]：",
    custom_key_prompt: "請輸入 32 位十六進位金鑰：",
    custom_key_invalid: "金鑰格式無效。",
    summary_header: "修補摘要",
    dynamic_title: "動態修補",
};

static EN: Strings = Strings {
    title: "Cairn Pro 1.0.1 kernel patcher",
    choose_language: "Choose output language:",
    lang_zhcn: "1. Simplified Chinese",
    lang_zhtw: "2. Traditional Chinese",
    lang_en: "3. English",
    need_tty: "An interactive terminal is required.",
    ask_image: "Kernel/boot image path (leave empty to exit): ",
    image_empty: "Path cannot be empty; nothing written.",
    scanning: "Identifying the image...",
    identified: "Device scheme identified: ",
    unknown_image: "No device scheme lookup; every site and offset is derived from this image.",
    not_android: "Not a valid Android boot image.",
    header_unsupported: "Only boot header v3/v4 is supported.",
    kernel_not_arm64: "Kernel is not an uncompressed ARM64 Image; gzip/LZ4 are not supported.",
    no_symbols: "No symbol table could be discovered in the image.",
    symbols_found: "Symbol table discovered; symbol count: ",
    resolving_hooks: "Resolving patch sites by symbol...",
    hook_ok: "match",
    hook_mismatch: "instruction mismatch",
    hook_missing: "symbol missing",
    refuse_incomplete: "Not every patch site could be verified; refusing to write an image.",
    refuse_experimental: "This scheme is experimental and not device-verified; refusing to write.",
    refuse_profile_targets: "This scheme still has unresolved kernel addresses; refusing to write.",
    building: "Building the image...",
    build_done: "Build complete.",
    output_dir: "Output: ",
    press_enter: "Press Enter to exit.",
    cancelled: "Cancelled; nothing written.",
    warn_avb: "WARNING: AVB is not re-signed. Flashing is at your own risk.",
    ask_custom_key: "Use a custom authorization key? [y/N]: ",
    custom_key_prompt: "Enter 32 hex characters: ",
    custom_key_invalid: "Invalid key format.",
    summary_header: "Patch summary",
    dynamic_title: "Dynamic patching",
};

pub fn parse_lang(s: &str) -> Option<Lang> {
    match s.trim() {
        "1" => Some(Lang::ZhCn),
        "2" => Some(Lang::ZhTw),
        "3" => Some(Lang::En),
        v if v.eq_ignore_ascii_case("zh-cn") || v.eq_ignore_ascii_case("zhcn")
            || v == "简体" || v == "简体中文" => Some(Lang::ZhCn),
        v if v.eq_ignore_ascii_case("zh-tw") || v.eq_ignore_ascii_case("zhtw")
            || v == "繁體" || v == "繁體中文" => Some(Lang::ZhTw),
        v if v.eq_ignore_ascii_case("en") || v.eq_ignore_ascii_case("english") => Some(Lang::En),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digits_and_names() {
        assert_eq!(parse_lang("1"), Some(Lang::ZhCn));
        assert_eq!(parse_lang("2"), Some(Lang::ZhTw));
        assert_eq!(parse_lang("3"), Some(Lang::En));
        assert_eq!(parse_lang("English"), Some(Lang::En));
        assert_eq!(parse_lang("4"), None);
        assert_eq!(parse_lang("klingon"), None);
    }

    #[test]
    fn every_language_has_text() {
        for l in [Lang::ZhCn, Lang::ZhTw, Lang::En] {
            let s = strings(l);
            assert!(!s.title.is_empty());
            assert!(!s.refuse_incomplete.is_empty());
            assert!(!s.hook_mismatch.is_empty());
        }
    }
}
