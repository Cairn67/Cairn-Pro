// Language selection and bilingual string lookup.
package com.cairn.manager;
import android.content.*;import android.content.res.Configuration;import android.os.LocaleList;import java.util.*;
public final class Language {
 static final String[] TAGS={"system","en","zh-CN","ja","ko","fr","de","es","pt","ru","it","id","vi","tr","ar"};
 static final String[] NAMES={"System default / 跟随系统","English","简体中文","日本語","한국어","Français","Deutsch","Español","Português","Русский","Italiano","Bahasa Indonesia","Tiếng Việt","Türkçe","العربية"};
 static final Map<String,String[]> WORDS=new HashMap<>();
 static { WORDS.put("Overview",new String[]{"概要","개요","Aperçu","Übersicht","Resumen","Visão geral","Обзор","Panoramica","Ringkasan","Tổng quan","Genel bakış","نظرة عامة"}); }
 static { WORDS.put("Apps",new String[]{"アプリ","앱","Applications","Apps","Aplicaciones","Aplicativos","Приложения","App","Aplikasi","Ứng dụng","Uygulamalar","التطبيقات"}); }
 static { WORDS.put("Logs",new String[]{"ログ","로그","Journaux","Protokolle","Registros","Registros","Журналы","Registri","Log","Nhật ký","Günlükler","السجلات"}); }
 static { WORDS.put("Settings",new String[]{"設定","설정","Paramètres","Einstellungen","Ajustes","Configurações","Настройки","Impostazioni","Pengaturan","Cài đặt","Ayarlar","الإعدادات"}); }
 static { WORDS.put("Modules",new String[]{"モジュール","모듈","Modules","Module","Módulos","Módulos","Модули","Moduli","Modul","Mô-đun","Modüller","الوحدات"}); }
 static { WORDS.put("Checking…",new String[]{"確認中…","확인 중…","Vérification…","Wird geprüft…","Comprobando…","Verificando…","Проверка…","Verifica…","Memeriksa…","Đang kiểm tra…","Denetleniyor…","جارٍ التحقق…"}); }
 static { WORDS.put("Clear",new String[]{"消去","지우기","Effacer","Leeren","Borrar","Limpar","Очистить","Cancella","Hapus","Xóa","Temizle","مسح"}); }
 static { WORDS.put("Close",new String[]{"閉じる","닫기","Fermer","Schließen","Cerrar","Fechar","Закрыть","Chiudi","Tutup","Đóng","Kapat","إغلاق"}); }
 static { WORDS.put("Copy",new String[]{"コピー","복사","Copier","Kopieren","Copiar","Copiar","Копировать","Copia","Salin","Sao chép","Kopyala","نسخ"}); }
 static { WORDS.put("Cairn Pro Manager",new String[]{"Cairn Pro マネージャー","Cairn Pro 관리자","Gestionnaire Cairn Pro","Cairn Pro-Manager","Gestor Cairn Pro","Gerenciador Cairn Pro","Менеджер Cairn Pro","Gestore Cairn Pro","Pengelola Cairn Pro","Trình quản lý Cairn Pro","Cairn Pro Yöneticisi","مدير Cairn Pro"}); }
 static { WORDS.put("System information",new String[]{"システム情報","시스템 정보","Informations système","Systeminformationen","Información del sistema","Informações do sistema","Сведения о системе","Informazioni di sistema","Informasi sistem","Thông tin hệ thống","Sistem bilgileri","معلومات النظام"}); }
 static { WORDS.put("Clear logs",new String[]{"ログを消去","로그 지우기","Effacer les journaux","Protokolle leeren","Borrar registros","Limpar registros","Очистить журналы","Cancella registri","Hapus log","Xóa nhật ký","Günlükleri temizle","مسح السجلات"}); }
 static { WORDS.put("About our project ↗",new String[]{"プロジェクトについて ↗","프로젝트 소개 ↗","À propos du projet ↗","Über das Projekt ↗","Acerca del proyecto ↗","Sobre o projeto ↗","О проекте ↗","Informazioni sul progetto ↗","Tentang proyek ↗","Giới thiệu dự án ↗","Proje hakkında ↗","حول المشروع ↗"}); }
 static { WORDS.put("Check again",new String[]{"再確認","다시 확인","Revérifier","Erneut prüfen","Comprobar de nuevo","Verificar novamente","Проверить снова","Ricontrolla","Periksa lagi","Kiểm tra lại","Yeniden denetle","إعادة التحقق"}); }
 static { WORDS.put("Working…",new String[]{"処理中…","처리 중…","En cours…","Wird ausgeführt…","Procesando…","Processando…","Выполнение…","In corso…","Memproses…","Đang xử lý…","İşleniyor…","جارٍ التنفيذ…"}); }
 static { WORDS.put("Not installed",new String[]{"未インストール","설치되지 않음","Non installé","Nicht installiert","No instalado","Não instalado","Не установлено","Non installato","Belum terpasang","Chưa cài đặt","Yüklü değil","غير مثبت"}); }
 static { WORDS.put("Refresh",new String[]{"更新","새로고침","Actualiser","Aktualisieren","Actualizar","Atualizar","Обновить","Aggiorna","Segarkan","Làm mới","Yenile","تحديث"}); }
 static { WORDS.put("Disabled",new String[]{"無効","비활성화","Désactivé","Deaktiviert","Desactivado","Desativado","Отключено","Disattivato","Nonaktif","Đã tắt","Devre dışı","معطّل"}); }
 static { WORDS.put("Reboot required",new String[]{"再起動が必要","재부팅 필요","Redémarrage requis","Neustart erforderlich","Reinicio necesario","Reinicialização necessária","Нужна перезагрузка","Riavvio necessario","Perlu mulai ulang","Cần khởi động lại","Yeniden başlatma gerekli","تلزم إعادة التشغيل"}); }
 static { WORDS.put("Enabled",new String[]{"有効","활성화","Activé","Aktiviert","Activado","Ativado","Включено","Attivato","Aktif","Đã bật","Etkin","مفعّل"}); }
 static { WORDS.put("Cancel",new String[]{"キャンセル","취소","Annuler","Abbrechen","Cancelar","Cancelar","Отмена","Annulla","Batal","Hủy","İptal","إلغاء"}); }
 static { WORDS.put("Confirm",new String[]{"確認","확인","Confirmer","Bestätigen","Confirmar","Confirmar","Подтвердить","Conferma","Konfirmasi","Xác nhận","Onayla","تأكيد"}); }
 static { WORDS.put("Done",new String[]{"完了","완료","Terminé","Fertig","Listo","Concluído","Готово","Fatto","Selesai","Xong","Tamamlandı","تم"}); }
 static { WORDS.put("Open WebUI",new String[]{"WebUI を開く","WebUI 열기","Ouvrir WebUI","WebUI öffnen","Abrir WebUI","Abrir WebUI","Открыть WebUI","Apri WebUI","Buka WebUI","Mở WebUI","WebUI aç","فتح WebUI"}); }
 static { WORDS.put("Allow",new String[]{"許可","허용","Autoriser","Erlauben","Permitir","Permitir","Разрешить","Consenti","Izinkan","Cho phép","İzin ver","السماح"}); }
 static { WORDS.put("View only",new String[]{"閲覧のみ","보기만","Lecture seule","Nur ansehen","Solo ver","Somente visualizar","Только просмотр","Solo visualizzazione","Lihat saja","Chỉ xem","Yalnızca görüntüle","عرض فقط"}); }
 static { WORDS.put("Language",new String[]{"言語","언어","Langue","Sprache","Idioma","Idioma","Язык","Lingua","Bahasa","Ngôn ngữ","Dil","اللغة"}); }
 static { WORDS.put("System default",new String[]{"システム設定に従う","시스템 기본값","Langue du système","Systemstandard","Idioma del sistema","Padrão do sistema","Язык системы","Lingua di sistema","Ikuti sistem","Theo hệ thống","Sistem varsayılanı","لغة النظام"}); }
 static { WORDS.put("App management",new String[]{"アプリ管理","앱 관리","Gestion des applications","App-Verwaltung","Gestión de aplicaciones","Gerenciar aplicativos","Управление приложениями","Gestione app","Kelola aplikasi","Quản lý ứng dụng","Uygulama yönetimi","إدارة التطبيقات"}); }
 static { WORDS.put("App visibility",new String[]{"アプリの可視性","앱 가시성","Visibilité des applications","App-Sichtbarkeit","Visibilidad de aplicaciones","Visibilidade de aplicativos","Видимость приложений","Visibilità app","Visibilitas aplikasi","Khả năng hiển thị ứng dụng","Uygulama görünürlüğü","ظهور التطبيقات"}); }
 static { WORDS.put("Save draft",new String[]{"下書きを保存","초안 저장","Enregistrer le brouillon","Entwurf speichern","Guardar borrador","Salvar rascunho","Сохранить черновик","Salva bozza","Simpan draf","Lưu bản nháp","Taslağı kaydet","حفظ المسودة"}); }
 static { WORDS.put("Clear selection",new String[]{"選択を解除","선택 해제","Tout désélectionner","Auswahl aufheben","Borrar selección","Limpar seleção","Снять выделение","Cancella selezione","Hapus pilihan","Bỏ chọn tất cả","Seçimi temizle","مسح التحديد"}); }
 static { WORDS.put("Search apps or packages",new String[]{"アプリ名・パッケージ名を検索","앱 또는 패키지 검색","Rechercher une application ou un paquet","Apps oder Pakete suchen","Buscar aplicaciones o paquetes","Buscar aplicativos ou pacotes","Поиск приложений или пакетов","Cerca app o pacchetti","Cari aplikasi atau paket","Tìm ứng dụng hoặc gói","Uygulama veya paket ara","البحث عن تطبيق أو حزمة"}); }
 static { WORDS.put("Visibility not enabled",new String[]{"非表示機能は未有効","숨김 기능 비활성화","Masquage désactivé","Ausblenden deaktiviert","Ocultación desactivada","Ocultação desativada","Скрытие отключено","Occultamento disattivato","Penyembunyian nonaktif","Chưa bật tính năng ẩn","Gizleme etkin değil","الإخفاء غير مفعّل"}); }
 static { WORDS.put("Draft · not active",new String[]{"下書き · 未適用","초안 · 적용되지 않음","Brouillon · inactif","Entwurf · nicht aktiv","Borrador · inactivo","Rascunho · inativo","Черновик · не действует","Bozza · non attiva","Draf · belum aktif","Bản nháp · chưa có hiệu lực","Taslak · etkin değil","مسودة · غير مطبقة"}); }
 static { WORDS.put("ROOT authorization",new String[]{"ROOT 権限","ROOT 권한","Autorisation ROOT","ROOT-Berechtigung","Autorización ROOT","Autorização ROOT","Разрешение ROOT","Autorizzazione ROOT","Otorisasi ROOT","Quyền ROOT","ROOT yetkisi","صلاحية ROOT"}); }
 static org.json.JSONObject catalog;
 static synchronized String translated(Context c,String lang,String key){try{if(catalog==null){try(java.io.InputStream in=c.getAssets().open("ui-translations.json")){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);catalog=new org.json.JSONObject(out.toString("UTF-8"));}}org.json.JSONObject table=catalog.optJSONObject(lang);return table==null?null:table.optString(key,null);}catch(Exception e){return null;}}
 public static Context wrap(Context c){String tag=c.getSharedPreferences("language",0).getString("selection","system");Configuration cfg=new Configuration(c.getResources().getConfiguration());if(!tag.equals("system")){Locale locale=Locale.forLanguageTag(tag);cfg.setLocales(new LocaleList(locale));cfg.setLayoutDirection(locale);}return c.createConfigurationContext(cfg);}
 public static String text(Context c,String en,String zh){String lang=c.getResources().getConfiguration().getLocales().get(0).getLanguage();if(lang.equals("zh"))return zh;String local=translated(c,lang,en);if(local!=null)return local;String[] values=WORDS.get(en);if(values!=null)for(int i=3;i<TAGS.length;i++)if(TAGS[i].equals(lang))return values[i-3];return en;}
 static int selectedIndex(Context c) {
  String tag = c.getSharedPreferences("language", 0).getString("selection", "system");
  for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return i;
  return 0;
 }
 static String[] choices(Context c) {
  String[] labels = NAMES.clone();
  labels[0] = text(c, "System default", "跟随系统");
  return labels;
 }
 static void applyChoice(android.app.Activity a, int index) {
  if (index < 0 || index >= TAGS.length || index == selectedIndex(a)) return;
  a.getSharedPreferences("language", 0).edit().putString("selection", TAGS[index]).apply();
  a.recreate();
 }
}
