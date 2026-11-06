import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w') as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        ni.create_system = info.create_system
        ni.extra = b''
        if ni.compress_type == zipfile.ZIP_STORED:
            align = 16384 if info.filename.endswith('.so') else 4
            off = zout.fp.tell() + 30 + len(ni.filename.encode('utf-8'))
            pad = (-off) % align
            if pad and pad < 4:
                pad += align
            if pad:
                # extra field: id 0xD935 (alignment), size, then padding
                ni.extra = (0xD935).to_bytes(2,'little') + (pad-4).to_bytes(2,'little') + b'\0'*(pad-4)
        zout.writestr(ni, data)
