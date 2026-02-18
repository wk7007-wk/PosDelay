"""
GENESIS BBQ POS 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywinauto requests pillow pytesseract
  3. Tesseract OCR 설치: https://github.com/UB-Mannheim/tesseract/wiki
     → 설치 시 "Additional language data" 에서 Korean 체크
  4. 이 파일 실행: python mate_monitor.py

처음 실행 시 설정 자동 안내됩니다.
"""

import ctypes
import json
import os
import subprocess
import sys
import time
import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(SCRIPT_DIR, "config.json")
LOCK_FILE = os.path.join(SCRIPT_DIR, "monitor.lock")

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
    print("\n=== 첫 실행: 설정 파일 생성 ===\n")
    print("GitHub Personal Access Token이 필요합니다.")
    print("  1. https://github.com/settings/tokens/new 접속")
    print("  2. Note: PosDelay2")
    print("  3. Expiration: No expiration")
    print("  4. 체크: gist")
    print("  5. Generate token 클릭 → 토큰 복사\n")
    token = input("토큰 붙여넣기: ").strip()
    cfg = DEFAULT_CONFIG.copy()
    cfg["github_token"] = token
    save_config(cfg)
    print(f"\n[OK] config.json 생성 완료: {CONFIG_FILE}")
    return cfg


def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


def dismiss_popup():
    """MATE POS 팝업 자동 닫기"""
    from pywinauto import Application
    try:
        app = Application(backend="uia").connect(title="MATE POS", timeout=3)
        win = app.window(title="MATE POS")
        texts = [c.window_text() for c in win.descendants() if c.window_text()]
        if "실행 중입니다" in " ".join(texts):
            print("[OK] MATE POS 팝업 자동 닫기")
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
    """POS 메인 창에 연결"""
    from pywinauto import Application, findwindows
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
            print(f"[OK] POS 연결: {win.window_text()} ({backend})")
            return app, win
        except Exception:
            pass

    print(f"\n[!] '{keyword}' 창을 찾을 수 없습니다.")
    try:
        for w in findwindows.find_elements():
            if w.name.strip():
                print(f"  - {w.name}")
    except Exception:
        pass
    return None, None


def ensure_window_visible(win):
    """최소화된 창 자동 복원"""
    try:
        hwnd = win.handle
        if ctypes.windll.user32.IsIconic(hwnd):
            SW_RESTORE = 9
            ctypes.windll.user32.ShowWindow(hwnd, SW_RESTORE)
            time.sleep(1)
            print(f"[{time.strftime('%H:%M:%S')}] POS 최소화 감지 → 복원")
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

    # PrintWindow (PW_RENDERFULLCONTENT = 2)
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
        print(f"[!] 캡처 실패: {e}")
        return None, None

    # 전체 창 OCR
    try:
        w, h = img.size
        scaled = img.resize((w * 2, h * 2), Image.LANCZOS)
        gray = scaled.convert("L")
        bw = gray.point(lambda x: 255 if x > 128 else 0, "1")

        text = pytesseract.image_to_string(bw, lang="kor+eng", config="--psm 6").strip()
        count = _count_delivery_processing(text)
        if count is not None:
            return count, f"배달+처리중: {count}건"

        # 반전 시도
        inverted = ImageOps.invert(gray)
        bw_inv = inverted.point(lambda x: 255 if x > 128 else 0, "1")
        text2 = pytesseract.image_to_string(bw_inv, lang="kor+eng", config="--psm 6").strip()
        count2 = _count_delivery_processing(text2)
        if count2 is not None:
            return count2, f"배달+처리중(inv): {count2}건"

    except Exception as e:
        print(f"[!] OCR 오류: {e}")

    return None, None


def _count_delivery_processing(text):
    """OCR 텍스트에서 배달 주문 행 수 카운트 (활성 상태만)"""
    if not text:
        return None

    lines = text.split("\n")
    delivery_found = False
    count = 0
    unmatched = []
    for line in lines:
        has_delivery = "배달" in line or "배닫" in line or "베달" in line
        if not has_delivery:
            continue
        delivery_found = True
        has_active = (
            "접수" in line or "접쑤" in line or "점수" in line
            or "처리중" in line or "저리중" in line or "처리종" in line or "저디중" in line
            or "조리시작" in line or "초리시작" in line or "조리시직" in line
            or "조리완료" in line or "초리완료" in line or "조리완르" in line
            or "배달중" in line or "배닫중" in line or "베달중" in line
            or "픽업" in line or "픽엄" in line
            or "준비" in line
        )
        if has_active:
            count += 1
        else:
            unmatched.append(line.strip()[:50])

    if delivery_found:
        if unmatched:
            print(f"[!] 배달 미매칭 {len(unmatched)}행: {unmatched[:3]}")
        return count
    return None


def update_gist(cfg, count):
    """GitHub Gist에 주문 건수 업데이트"""
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
        print(f"[!] 네트워크 오류: {e}")
        return False


def kill_old_instances():
    """기존 인스턴스 종료 (PID 락 파일 기반)"""
    if os.path.exists(LOCK_FILE):
        try:
            with open(LOCK_FILE, "r") as f:
                old_pid = int(f.read().strip())
            import signal
            os.kill(old_pid, signal.SIGTERM)
            print(f"[OK] 기존 인스턴스 종료 (PID {old_pid})")
            time.sleep(1)
        except (ProcessLookupError, ValueError):
            pass
        except Exception:
            try:
                os.system(f"taskkill /PID {old_pid} /F >nul 2>&1")
            except Exception:
                pass
    with open(LOCK_FILE, "w") as f:
        f.write(str(os.getpid()))


SCRIPT_DIR_ROOT = os.path.dirname(SCRIPT_DIR)  # PosDelay/ 루트


def auto_update():
    """git pull 후 변경 있으면 자동 재시작"""
    try:
        result = subprocess.run(
            ["git", "pull", "--ff-only"],
            cwd=SCRIPT_DIR_ROOT, capture_output=True, text=True, timeout=30,
        )
        output = result.stdout.strip()
        print(f"[{time.strftime('%H:%M:%S')}] git pull: {output}")

        if "Already up to date" in output or "Already up-to-date" in output:
            return  # 변경 없음

        # 변경 있음 → 재시작
        print(f"[{time.strftime('%H:%M:%S')}] 코드 업데이트 감지 → 재시작")
        try:
            os.remove(LOCK_FILE)
        except Exception:
            pass

        script = os.path.abspath(__file__)
        os.execv(sys.executable, [sys.executable, script])

    except subprocess.TimeoutExpired:
        print(f"[{time.strftime('%H:%M:%S')}] git pull 타임아웃")
    except Exception as e:
        print(f"[{time.strftime('%H:%M:%S')}] auto_update 실패: {e}")


def main():
    kill_old_instances()

    print("=" * 50)
    print(f"  GENESIS BBQ POS 주문 모니터 (PID {os.getpid()})")
    print("=" * 50)

    cfg = load_config()

    if not cfg["github_token"]:
        print("\n[!] GitHub 토큰이 없습니다.")
        print("  1. https://github.com/settings/tokens/new → gist 체크\n")
        token = input("토큰 붙여넣기: ").strip()
        if token:
            cfg["github_token"] = token
            save_config(cfg)
        else:
            print("[!] 토큰 필요"); return

    # OCR 확인
    try:
        import pytesseract
        tesseract_path = cfg.get("tesseract_path", "")
        if tesseract_path and os.path.exists(tesseract_path):
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
        ver = pytesseract.get_tesseract_version()
        print(f"[OK] Tesseract {ver}")
    except Exception:
        print("[!] Tesseract OCR 필요!")
        print("    https://github.com/UB-Mannheim/tesseract/wiki")
        input("\n엔터를 누르면 종료...")
        return

    # MATE POS 팝업 닫기
    dismiss_popup()

    # POS 연결
    app, win = connect_pos(cfg)
    if not win:
        input("\n엔터를 누르면 종료...")
        return

    # 초기 건수 확인
    count, matched = read_order_count(win, cfg)
    if count is not None:
        print(f"[OK] 주문 건수: {count}건 ({matched})")
    else:
        print("[!] 건수 감지 실패")

    # 모니터링 루프
    interval = cfg["poll_interval_sec"]
    print(f"\n{interval}초 간격 모니터링 시작 (매 정각 자동업데이트)... (Ctrl+C 종료)\n")

    last_count = -1
    fail_count = 0
    last_update_slot = -1
    while True:
        try:
            # 30분마다: git pull + 자동 재시작 (00분, 30분)
            t = time.localtime()
            current_slot = t.tm_hour * 2 + (1 if t.tm_min >= 30 else 0)
            if current_slot != last_update_slot:
                last_update_slot = current_slot
                auto_update()  # 변경 있으면 여기서 재시작됨

            # 창 재연결
            try:
                win.window_text()
            except Exception:
                print(f"[{time.strftime('%H:%M:%S')}] 창 재연결...")
                dismiss_popup()
                app, win = connect_pos(cfg)
                if not win:
                    time.sleep(interval)
                    continue

            # 최소화 복원
            ensure_window_visible(win)

            # 건수 읽기
            count, matched = read_order_count(win, cfg)

            if count is not None:
                fail_count = 0
                changed = count != last_count
                if changed:
                    print(f"[{time.strftime('%H:%M:%S')}] 주문: {last_count}→{count}건 [{matched}]")
                update_gist(cfg, count)
                last_count = count
            else:
                fail_count += 1
                if fail_count % 10 == 1:
                    print(f"[{time.strftime('%H:%M:%S')}] 건수 감지 실패 ({fail_count}회)")

            time.sleep(interval)

        except KeyboardInterrupt:
            print("\n모니터링 종료")
            break
        except Exception as e:
            print(f"[!] 오류: {e}")
            time.sleep(interval)


if __name__ == "__main__":
    main()
