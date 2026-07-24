import sys, re, subprocess, time

ADB = r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools/adb.exe'
DEV = '21770d7d'

def shell(cmd):
    r = subprocess.run([ADB, '-s', DEV, 'shell', cmd], capture_output=True)
    return r.stdout.decode('utf-8', errors='replace')

def dump_texts():
    shell('uiautomator dump /sdcard/ui.xml')
    x = shell('cat /sdcard/ui.xml')
    return x

def find_button(text):
    x = dump_texts()
    for node in re.findall(r'<node[^>]*>', x):
        tm = re.search(r'text="([^"]*)"', node)
        if not tm:
            tm = re.search(r"text='([^']*)'", node)
        if tm and tm.group(1) == text:
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if bm:
                cx = (int(bm.group(1)) + int(bm.group(3))) // 2
                cy = (int(bm.group(2)) + int(bm.group(4))) // 2
                return cx, cy
    return None

def all_texts(x):
    out = []
    for node in re.findall(r'<node[^>]*>', x):
        tm = re.search(r'text="([^"]*)"', node) or re.search(r"text='([^']*)'", node)
        if tm and tm.group(1):
            out.append(tm.group(1))
    return out

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    cmd = sys.argv[1]
    if cmd == 'tap':
        target = sys.argv[2]
        if '\\u' in target:
            target = target.encode().decode('unicode_escape')
        pos = find_button(target)
        if pos:
            print('tap', repr(target), pos)
            shell(f'input tap {pos[0]} {pos[1]}')
        else:
            print('NOT FOUND:', repr(target))
    elif cmd == 'texts':
        for t in all_texts(dump_texts()):
            if any(k in t for k in ('proot', '状态', 'Linux')):
                print(t[:200])
