"""Add classes.dex to the aapt2-linked base APK (unsigned output)."""
import sys
import zipfile

base, dex, out = sys.argv[1:4]
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for info in zin.infolist():
        zout.writestr(info, zin.read(info.filename))
    zout.write(dex, "classes.dex")
print("built", out)
