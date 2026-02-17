"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유 (백그라운드)
로그: pc/monitor_log.txt

사용법:
  1. mate_monitor.py를 먼저 한번 실행 (config.json 생성)
  2. 더블클릭: mate_monitor.pyw (창 없이 백그라운드)
"""

import ctypes
import ctypes.wintypes
import json
import logging
import os
import re
import sys
import time
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(SCRIPT_DIR, "config.json")
LOG_FILE = os.path.join(SCRIPT_DIR, "monitor_log.txt")

logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    encoding="utf-8",
)
log = logging.getLogger("mate_monitor")

DEFAULT_CONFIG = {
    "github_token": "",
    "gist_id": "a67e5de3271d6d0716b276dc6a8391cb",
    "window_title": "메인",
    "delivery_tab_id": "198354",
    "processing_tab_id": "133094",
    "poll_interval_sec": 30,
    "tesseract_path": r"C:\Program Files\Tesseract-OCR\tesseract.exe",
}


def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
            for k, v in DEFAULT_CONFIG.items():
                if k not in cfg:
                    cfg[k] = v
            return cfg
    return None


def dismiss_popup():
    from pywinauto import Application
    try:
        app = Application(backend="uia").connect(title="MATE POS", timeout=3)
        win = app.window(title="MATE POS")
        texts = [c.window_text() for c in win.descendants() if c.window_text()]
        if "실행 중입니다" in " ".join(texts):
            log.info("MATE POS 팝업 자동 닫기")
            try:
                btn = win.child_window(title="확인")
                try:
                    btn.invoke()
                except Exception:
                    try:
                        btn.click()
                    except Exception:
                        btn.click_input()
                time.sleep(1)
            except Exception:
                pass
    except Exception:
        pass


def connect_pos(cfg):
    from pywinauto import Application
    keyword = cfg["window_title"]
    for backend in ["uia", "win32"]:
        try:
            app = Application(backend=backend).connect(title_re=f".*{keyword}.*", timeout=5)
            win = app.window(title_re=f".*{keyword}.*")
            try:
                child_texts = [c.window_text() for c in win.descendants() if c.window_text()]
                if "실행 중입니다" in " ".join(child_texts) and len(child_texts) < 6:
                    continue
            except Exception:
                pass
            log.info(f"[OK] POS 연결: {win.window_text()} ({backend})")
            return app, win
        except Exception:
            pass
    log.warning(f"[!] '{keyword}' 창 없음")
    return None, None


def is_mouse_active():
    try:
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        pt1 = POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt1))
        time.sleep(1)
        pt2 = POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt2))
        return pt1.x != pt2.x or pt1.y != pt2.y
    except Exception:
        return False


def click_delivery_tab(win, tab_id):
    if is_mouse_active():
        return False
    try:
        tab = win.child_window(auto_id=tab_id)
        if tab.exists(timeout=2):
            try:
                tab.invoke()
                return True
            except Exception:
                pass
            try:
                tab.click()
                return True
            except Exception:
                pass
            tab.click_input()
            return True
    except Exception:
        pass
    return False


def capture_window_bg(hwnd):
    """PrintWindow API로 창이 가려져도 캡처"""
    import win32gui
    import win32ui
    from PIL import Image

    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    w = right - left
    h = bottom - top

    hwndDC = win32gui.GetWindowDC(hwnd)
    mfcDC = win32ui.CreateDCFromHandle(hwndDC)
    saveDC = mfcDC.CreateCompatibleDC()

    bitmap = win32ui.CreateBitmap()
    bitmap.CreateCompatibleBitmap(mfcDC, w, h)
    saveDC.SelectObject(bitmap)

    ctypes.windll.user32.PrintWindow(hwnd, saveDC.GetSafeHdc(), 2)

    bmpinfo = bitmap.GetInfo()
    bmpstr = bitmap.GetBitmapBits(True)
    img = Image.frombuffer(
        'RGB', (bmpinfo['bmWidth'], bmpinfo['bmHeight']),
        bmpstr, 'raw', 'BGRX', 0, 1
    )

    win32gui.DeleteObject(bitmap.GetHandle())
    saveDC.DeleteDC()
    mfcDC.DeleteDC()
    win32gui.ReleaseDC(hwnd, hwndDC)

    return img


def read_order_count(win, cfg):
    """처리중 서브탭 캡처 → 4배 확대 → OCR"""
    import pytesseract
    from PIL import Image, ImageOps

    tesseract_path = cfg.get("tesseract_path", "")
    if tesseract_path and os.path.exists(tesseract_path):
        pytesseract.pytesseract.tesseract_cmd = tesseract_path

    try:
        hwnd = win.handle
        img = capture_window_bg(hwnd)
        win_rect = win.rectangle()

        proc_id = cfg.get("processing_tab_id", "133094")
        elem = win.child_window(auto_id=proc_id)
        rect = elem.rectangle()
        x1 = rect.left - win_rect.left
        y1 = rect.top - win_rect.top
        x2 = rect.right - win_rect.left
        y2 = rect.bottom - win_rect.top
        cropped = img.crop((x1, y1, x2, y2))

        w, h = cropped.size
        scaled = cropped.resize((w * 4, h * 4), Image.LANCZOS)
        gray = scaled.convert("L")
        bw = gray.point(lambda x: 255 if x > 128 else 0, "1")

        text = pytesseract.image_to_string(bw, lang="kor+eng", config="--psm 7").strip()
        if text:
            m = re.search(r"(\d+)", text)
            if m:
                return int(m.group(1)), f"OCR: {text}"

        inverted = ImageOps.invert(gray)
        bw_inv = inverted.point(lambda x: 255 if x > 128 else 0, "1")
        text2 = pytesseract.image_to_string(bw_inv, lang="kor+eng", config="--psm 7").strip()
        if text2:
            m = re.search(r"(\d+)", text2)
            if m:
                return int(m.group(1)), f"OCR(inv): {text2}"

    except Exception as e:
        log.warning(f"[!] OCR 오류: {e}")

    return None, None


def update_gist(cfg, count):
    token = cfg["github_token"]
    if not token:
        return False
    url = f"https://api.github.com/gists/{cfg['gist_id']}"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
    }
    data = {
        "files": {
            "order_status.json": {
                "content": json.dumps({
                    "count": count,
                    "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "source": "pc",
                }, ensure_ascii=False)
            }
        }
    }
    try:
        resp = requests.patch(url, headers=headers, json=data, timeout=10)
        return resp.status_code == 200
    except Exception as e:
        log.warning(f"[!] 네트워크 오류: {e}")
        return False


def register_startup():
    try:
        import winreg
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        pyw_path = os.path.join(SCRIPT_DIR, "mate_monitor.pyw")
        pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
        if not os.path.exists(pythonw):
            pythonw = "pythonw"
        cmd = f'"{pythonw}" "{pyw_path}"'
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE)
        winreg.SetValueEx(key, "PosDelayMonitor", 0, winreg.REG_SZ, cmd)
        winreg.CloseKey(key)
        log.info(f"[OK] 시작프로그램 등록: {cmd}")
    except Exception as e:
        log.warning(f"[!] 시작프로그램 등록 실패: {e}")


def main():
    log.info("=" * 40)
    log.info("  PosDelay PC 모니터 시작 (백그라운드)")
    log.info("=" * 40)

    cfg = load_config()
    if not cfg or not cfg.get("github_token"):
        log.error("[!] config.json 없음. mate_monitor.py를 먼저 실행하세요.")
        return

    try:
        import pytesseract
        tesseract_path = cfg.get("tesseract_path", "")
        if tesseract_path and os.path.exists(tesseract_path):
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        pytesseract.get_tesseract_version()
        log.info("[OK] Tesseract OCR 확인")
    except Exception:
        log.error("[!] Tesseract OCR 없음. 종료.")
        return

    register_startup()
    dismiss_popup()

    app, win = None, None
    for attempt in range(10):
        dismiss_popup()
        app, win = connect_pos(cfg)
        if win:
            break
        log.info(f"[{attempt+1}/10] POS 대기 중...")
        time.sleep(30)

    if not win:
        log.error("[!] POS 연결 실패. 종료.")
        return

    tab_id = cfg["delivery_tab_id"]
    interval = cfg["poll_interval_sec"]
    log.info(f"모니터링 시작 ({interval}초 간격)")

    last_count = -1
    fail_count = 0
    while True:
        try:
            try:
                win.window_text()
            except Exception:
                log.info("창 재연결...")
                dismiss_popup()
                app, win = connect_pos(cfg)
                if not win:
                    time.sleep(interval)
                    continue

            click_delivery_tab(win, tab_id)
            time.sleep(0.5)

            count, matched = read_order_count(win, cfg)

            if count is not None:
                fail_count = 0
                if count != last_count:
                    log.info(f"주문: {count}건 ({last_count}→{count}) [{matched}]")
                    if update_gist(cfg, count):
                        log.info("Gist 업데이트 완료")
                    last_count = count
            else:
                fail_count += 1
                if fail_count % 10 == 1:
                    log.warning(f"건수 감지 실패 ({fail_count}회)")

            time.sleep(interval)

        except KeyboardInterrupt:
            log.info("모니터링 종료")
            break
        except Exception as e:
            log.error(f"오류: {e}")
            time.sleep(interval)


if __name__ == "__main__":
    main()
