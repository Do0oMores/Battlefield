"""DPI 感知的窗口截图（本机 150% 缩放，skill 里的 shot.py 用的是逻辑坐标，会截偏）。

用法:
    python winshot.py --list
    python winshot.py --hwnd 3082464 --out shot.png [--maximize] [--key F3]
"""
import argparse
import ctypes
import ctypes.wintypes
import time

from PIL import ImageGrab

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)  # PER_MONITOR_DPI_AWARE
except Exception:
    ctypes.windll.user32.SetProcessDPIAware()

user32 = ctypes.windll.user32
WM_KEYDOWN, WM_KEYUP = 0x0100, 0x0101
VK = {"F1": 0x70, "F3": 0x72, "ESC": 0x1B}

EnumWindowsProc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND, ctypes.wintypes.LPARAM)


def visible_windows():
    found = []
    buf = ctypes.create_unicode_buffer(512)

    def cb(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        n = user32.GetWindowTextW(hwnd, buf, 512)
        title = buf.value
        if "Minecraft" in title:
            r = ctypes.wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(r))
            pid = ctypes.wintypes.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            found.append((hwnd, pid.value, title, r.left, r.top, r.right - r.left, r.bottom - r.top))
        return True

    user32.EnumWindows(EnumWindowsProc(cb), 0)
    return found


def tap_key(hwnd, name):
    vk = VK[name]
    user32.PostMessageW(hwnd, WM_KEYDOWN, vk, 0)
    time.sleep(0.05)
    user32.PostMessageW(hwnd, WM_KEYUP, vk, 0)
    time.sleep(0.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--hwnd", type=int, default=0)
    ap.add_argument("--out", default="shot.png")
    ap.add_argument("--maximize", action="store_true")
    ap.add_argument("--move", default="", help="x,y 把窗口挪到指定位置，避免两个窗口完全重叠")
    ap.add_argument("--key", default="")
    ap.add_argument("--hwnd2", type=int, default=0, help="第二个窗口（与 --hwnd2out 配对）")
    ap.add_argument("--hwnd2out", default="")
    args = ap.parse_args()

    if args.list:
        for w in visible_windows():
            print(f"hwnd={w[0]} pid={w[1]} {w[3]},{w[4]} {w[5]}x{w[6]} {w[2]}")
        return

    hwnd = args.hwnd
    if args.move:
        x, y = (int(v) for v in args.move.split(","))
        r = ctypes.wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        user32.SetWindowPos(hwnd, 0, x, y, r.right - r.left, r.bottom - r.top, 0x0004)
        time.sleep(0.6)
    if args.maximize:
        user32.ShowWindow(hwnd, 3)  # SW_MAXIMIZE
        time.sleep(1.0)
    for name in filter(None, args.key.split(",")):
        tap_key(hwnd, name)

    r = ctypes.wintypes.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(r))
    ImageGrab.grab(bbox=(r.left, r.top, r.right, r.bottom)).save(args.out)
    print(f"saved {args.out} bbox={r.left},{r.top},{r.right},{r.bottom}")


if __name__ == "__main__":
    main()
