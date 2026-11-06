"""Patches the apktool-decoded Duo Launcher tree to use the App Library page."""
import pathlib, sys

dec = pathlib.Path(sys.argv[1])

# 1. Library page in the home pager: replace the "All apps" list composable call.
s5 = dec / "smali/G1/s5.smali"
old = ("invoke-static/range {v3 .. v18}, LI1/y;->b(LG1/L5;Ljava/lang/String;LU1/c;LU1/c;LU1/e;"
       "LU1/c;La0/r;ZLG1/B2;Ljava/lang/Integer;LU1/e;LU1/c;LO/o;III)V")
new = "invoke-static {v3, v9, v15}, Lcom/jake/duolauncher/applib/AppLibrary;->content(LG1/L5;La0/r;LO/o;)V"
text = s5.read_text()
if text.count(old) != 1:
    sys.exit("hook site not found in G1/s5.smali (unexpected APK version?)")
s5.write_text(text.replace(old, new))

# 2. Allow "Uninstall" from the App Library long-press menu.
manifest = dec / "AndroidManifest.xml"
m = manifest.read_text()
perm = '<uses-permission android:name="android.permission.REQUEST_DELETE_PACKAGES"/>'
if perm not in m:
    m = m.replace('<uses-permission android:name="android.permission.BIND_APPWIDGET"/>',
                  '<uses-permission android:name="android.permission.BIND_APPWIDGET"/>\n    ' + perm, 1)
    manifest.write_text(m)

# 3. Mark the build.
yml = dec / "apktool.yml"
y = yml.read_text()
yml.write_text(y.replace("versionName: 0.15.0-beta01", "versionName: 0.15.0-beta01-applibrary"))
print("patched")
