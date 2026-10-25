import ctypes
import sys
import time

from PIL import ImageGrab

user32 = ctypes.windll.user32


def focus(hwnd: int) -> None:
    user32.ShowWindow(hwnd, 9)      # SW_RESTORE
    user32.SetForegroundWindow(hwnd)
    time.sleep(1.2)


def shoot(path: str, hwnd: int | None = None) -> None:
    if hwnd:
        rect = ctypes.wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        bbox = (rect.left, rect.top, rect.right, rect.bottom)
        ImageGrab.grab(bbox=bbox, all_screens=True).save(path)
    else:
        ImageGrab.grab(all_screens=True).save(path)
    print("saved", path)


if __name__ == "__main__":
    import ctypes.wintypes  # noqa: F401  (needed for RECT)

    hwnd = int(sys.argv[2]) if len(sys.argv) > 2 else None
    if hwnd:
        focus(hwnd)
    shoot(sys.argv[1], hwnd)
