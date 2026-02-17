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
import logging.handlers
import os
import re
import sys
import time
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(SCRIPT_DIR, "config.json")
LOG_FILE = os.path.join(SCRIPT_DIR, "monitor_log.txt")
LOCK_FILE = os.path.join(SCRIPT_DIR, "monitor.lock")

log = logging.getLogger("mate_monitor")
log.setLevel(logging.INFO)
_handler = logging.handlers.RotatingFileHandler(
    LOG_FILE, maxBytes=500_000, backupCount=2, encoding="utf-8"
)
_handler.setFormatter(logging.Formatter("%(asctime)s %(message)s", "%Y-%m-%d %H:%M:%S"))
log.addHandler(_handler)

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


def ensure_window_visible(win):
    """최소화된 창 자동 복원"""
    try:
        hwnd = win.handle
        if ctypes.windll.user32.IsIconic(hwnd):
            SW_RESTORE = 9
            ctypes.windll.user32.ShowWindow(hwnd, SW_RESTORE)
            time.sleep(1)
            log.info("POS 최소화 감지 → 복원")
            return True
    except Exception:
        pass
    return False


_ocr_dump_count = 0

def _log_ocr_text(text, label):
    """OCR 결과 텍스트를 로그에 덤프 (디버깅용, 처음 5회만)"""
    global _ocr_dump_count
    if _ocr_dump_count >= 5:
        return
    _ocr_dump_count += 1
    log.info(f"=== OCR 텍스트 ({label}) [{_ocr_dump_count}/5] ===")
    for i, line in enumerate(text.split("\n")):
        if line.strip():
            log.info(f"  L{i}: {line.strip()}")
    log.info("=== OCR 끝 ===")


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
    """배달+처리중 건수: 전체 창 OCR → 배달 행에서 처리중 카운트"""
    import pytesseract
    from PIL import Image, ImageOps

    tesseract_path = cfg.get("tesseract_path", "")
    if tesseract_path and os.path.exists(tesseract_path):
        pytesseract.pytesseract.tesseract_cmd = tesseract_path

    try:
        hwnd = win.handle
        img = capture_window_bg(hwnd)
    except Exception as e:
        log.warning(f"캡처 실패: {e}")
        return None, None

    # 전체 창 OCR
    try:
        w, h = img.size
        scaled = img.resize((w * 2, h * 2), Image.LANCZOS)
        gray = scaled.convert("L")
        bw = gray.point(lambda x: 255 if x > 128 else 0, "1")

        text = pytesseract.image_to_string(bw, lang="kor+eng", config="--psm 6").strip()
        _log_ocr_text(text, "normal")
        count = _count_delivery_processing(text)
        if count is not None:
            return count, f"배달+처리중: {count}건"

        # 반전 시도
        inverted = ImageOps.invert(gray)
        bw_inv = inverted.point(lambda x: 255 if x > 128 else 0, "1")
        text2 = pytesseract.image_to_string(bw_inv, lang="kor+eng", config="--psm 6").strip()
        _log_ocr_text(text2, "inverted")
        count2 = _count_delivery_processing(text2)
        if count2 is not None:
            return count2, f"배달+처리중(inv): {count2}건"

    except Exception as e:
        log.warning(f"OCR 오류: {e}")

    return None, None


def _count_delivery_processing(text):
    """OCR 텍스트에서 '배달' + '처리중' 조합 행 수 카운트"""
    if not text:
        return None

    lines = text.split("\n")
    delivery_found = False
    count = 0
    for line in lines:
        has_delivery = "배달" in line or "배닫" in line or "베달" in line
        has_processing = ("처리중" in line or "저리중" in line or "처리종" in line or "저디중" in line
                         or "조리시작" in line or "초리시작" in line or "조리시직" in line)
        if has_delivery:
            delivery_found = True
            if has_processing:
                count += 1

    # 배달 행이 하나라도 있었다면 유효한 카운트 (0 포함)
    if delivery_found:
        return count
    return None


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


def kill_old_instances():
    """기존 인스턴스 종료 (PID 락 파일 기반)"""
    if os.path.exists(LOCK_FILE):
        try:
            with open(LOCK_FILE, "r") as f:
                old_pid = int(f.read().strip())
            # 기존 프로세스 강제 종료
            import signal
            os.kill(old_pid, signal.SIGTERM)
            log.info(f"기존 인스턴스 종료 (PID {old_pid})")
            time.sleep(1)
        except (ProcessLookupError, ValueError):
            pass  # 이미 종료됨
        except Exception as e:
            log.warning(f"기존 인스턴스 종료 실패: {e}")
            # Windows에서는 taskkill 시도
            try:
                os.system(f"taskkill /PID {old_pid} /F >nul 2>&1")
            except Exception:
                pass

    # 현재 PID 기록
    with open(LOCK_FILE, "w") as f:
        f.write(str(os.getpid()))


def main():
    kill_old_instances()

    log.info("=" * 40)
    log.info(f"  PosDelay PC 모니터 시작 (PID {os.getpid()})")
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

            ensure_window_visible(win)

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

    # 종료 시 락 파일 정리
    try:
        os.remove(LOCK_FILE)
    except Exception:
        pass


if __name__ == "__main__":
    main()
