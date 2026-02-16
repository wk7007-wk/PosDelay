"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywin32 pyautogui requests
  3. 이 파일 실행: python mate_monitor.py

처음 실행 시 설정 자동 안내됩니다.
"""

import json
import os
import re
import time
import ctypes
import ctypes.wintypes
import requests
import pyautogui

pyautogui.FAILSAFE = False  # 마우스 코너 이동 시 중단 방지

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULT_CONFIG = {
    "github_token": "",
    "gist_id": "a67e5de3271d6d0716b276dc6a8391cb",
    "window_title": "메이트",
    "poll_interval_sec": 30,
    "delivery_tab_x": 0,
    "delivery_tab_y": 0,
}


def load_config():
    if os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
            for k, v in DEFAULT_CONFIG.items():
                if k not in cfg:
                    cfg[k] = v
            return cfg
    # 첫 실행: config.json 자동 생성
    print("\n=== 첫 실행: 설정 파일 생성 ===\n")
    print("GitHub Personal Access Token이 필요합니다.")
    print("  1. https://github.com/settings/tokens/new 접속")
    print("  2. Note: PosDelay")
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


# --- Windows API로 창 텍스트 읽기 ---

EnumWindows = ctypes.windll.user32.EnumWindows
EnumChildWindows = ctypes.windll.user32.EnumChildWindows
GetWindowTextW = ctypes.windll.user32.GetWindowTextW
GetWindowTextLengthW = ctypes.windll.user32.GetWindowTextLengthW
IsWindowVisible = ctypes.windll.user32.IsWindowVisible
SetForegroundWindow = ctypes.windll.user32.SetForegroundWindow
WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND, ctypes.wintypes.LPARAM)


def get_window_text(hwnd):
    length = GetWindowTextLengthW(hwnd)
    if length == 0:
        return ""
    buf = ctypes.create_unicode_buffer(length + 1)
    GetWindowTextW(hwnd, buf, length + 1)
    return buf.value


def find_windows(keyword):
    """키워드를 포함하는 모든 보이는 창 찾기"""
    results = []

    def callback(hwnd, _):
        if IsWindowVisible(hwnd):
            title = get_window_text(hwnd)
            if title and keyword in title:
                results.append((hwnd, title))
        return True

    EnumWindows(WNDENUMPROC(callback), 0)
    return results


def get_child_texts(hwnd):
    """창 내부의 모든 자식 컨트롤 텍스트 수집"""
    texts = []

    def callback(child_hwnd, _):
        text = get_window_text(child_hwnd)
        if text.strip():
            texts.append(text.strip())
        return True

    EnumChildWindows(hwnd, WNDENUMPROC(callback), 0)
    return texts


def extract_order_count(texts):
    """텍스트 목록에서 처리중 건수 추출"""
    for text in texts:
        # "처리중3", "처리중 3건", "처리중: 3", "처리중(3)" 등
        m = re.search(r"(?:처리중|진행중|접수|대기)[\s:：()（）]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        m = re.search(r"(\d+)\s*건", text)
        if m:
            return int(m.group(1)), text
    return None, None


def click_delivery_tab(cfg, hwnd):
    """배달 카테고리 탭 클릭"""
    x = cfg.get("delivery_tab_x", 0)
    y = cfg.get("delivery_tab_y", 0)
    if x == 0 and y == 0:
        return
    # 메이트포스 창을 앞으로 가져오기
    try:
        SetForegroundWindow(hwnd)
    except Exception:
        pass
    time.sleep(0.3)
    pyautogui.click(x, y)
    time.sleep(0.5)


def setup_delivery_tab(cfg):
    """배달 탭 위치 설정 (마우스로 클릭)"""
    print("\n=== 배달 탭 위치 설정 ===")
    print("메이트포스에서 '배달' 카테고리 탭 위에 마우스를 올리세요.")
    print("5초 후 현재 마우스 위치를 저장합니다...")

    for i in range(5, 0, -1):
        print(f"  {i}초...", end="\r")
        time.sleep(1)

    x, y = pyautogui.position()
    cfg["delivery_tab_x"] = x
    cfg["delivery_tab_y"] = y
    save_config(cfg)
    print(f"\n[OK] 배달 탭 위치 저장: ({x}, {y})")
    return cfg


def update_gist(cfg, count):
    """GitHub Gist에 주문 건수 업데이트"""
    gist_id = cfg["gist_id"]
    token = cfg["github_token"]
    if not token:
        print("[!] github_token이 설정되지 않았습니다.")
        return False

    url = f"https://api.github.com/gists/{gist_id}"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
    }
    data = {
        "files": {
            "order_status.json": {
                "content": json.dumps(
                    {
                        "count": count,
                        "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                        "source": "pc",
                    },
                    ensure_ascii=False,
                )
            }
        }
    }

    try:
        resp = requests.patch(url, headers=headers, json=data, timeout=10)
        if resp.status_code == 200:
            return True
        else:
            print(f"[!] Gist 업데이트 실패: {resp.status_code}")
            return False
    except Exception as e:
        print(f"[!] 네트워크 오류: {e}")
        return False


def list_all_windows():
    """모든 보이는 창 목록"""
    windows = []

    def callback(hwnd, _):
        if IsWindowVisible(hwnd):
            title = get_window_text(hwnd)
            if title.strip():
                windows.append(title)
        return True

    EnumWindows(WNDENUMPROC(callback), 0)
    return windows


def main():
    print("=" * 50)
    print("  메이트포스 주문 모니터 (PosDelay PC)")
    print("=" * 50)

    cfg = load_config()

    # 토큰 확인
    if not cfg["github_token"]:
        print("\n[!] GitHub 토큰이 없습니다.")
        print("  1. https://github.com/settings/tokens/new 접속")
        print("  2. gist 체크 → Generate token → 복사\n")
        token = input("토큰 붙여넣기: ").strip()
        if token:
            cfg["github_token"] = token
            save_config(cfg)
        else:
            print("[!] 토큰 없이는 실행할 수 없습니다.")
            return

    # 메이트포스 창 찾기
    keyword = cfg["window_title"]
    windows = find_windows(keyword)

    if not windows:
        print(f"\n[!] '{keyword}' 포함 창을 찾을 수 없습니다.")
        print("\n현재 열린 창 목록:")
        for w in list_all_windows():
            print(f"  - {w}")
        print(f"\nconfig.json의 window_title을 수정하세요.")
        save_config(cfg)
        input("\n엔터를 누르면 종료...")
        return

    print(f"\n[OK] 메이트포스 창 발견: {windows[0][1]}")
    hwnd = windows[0][0]

    # 배달 탭 위치 설정
    if cfg.get("delivery_tab_x", 0) == 0:
        print("\n배달 탭 위치가 설정되지 않았습니다.")
        ans = input("지금 설정하시겠습니까? (y/n): ").strip().lower()
        if ans == "y":
            cfg = setup_delivery_tab(cfg)
        else:
            print("[!] 배달 탭 위치 없이 진행 (현재 보이는 텍스트만 읽음)")

    # 초기 테스트
    if cfg.get("delivery_tab_x", 0) != 0:
        click_delivery_tab(cfg, hwnd)
        time.sleep(1)

    texts = get_child_texts(hwnd)
    title = get_window_text(hwnd)
    all_texts = [title] + texts

    print(f"\n창 텍스트 ({len(all_texts)}개):")
    for t in all_texts[:15]:
        print(f"  [{t}]")

    count, matched = extract_order_count(all_texts)
    if count is not None:
        print(f"\n[OK] 주문 건수 감지: {count}건 (매칭: {matched})")
    else:
        print(f"\n[!] 주문 건수 자동 감지 실패. 텍스트 확인 후 패턴 수정 필요.")

    # 모니터링 루프
    print(f"\n{cfg['poll_interval_sec']}초 간격 모니터링 시작...")
    print("종료: Ctrl+C\n")

    last_count = -1
    while True:
        try:
            windows = find_windows(keyword)
            if not windows:
                print(f"[{time.strftime('%H:%M:%S')}] 메이트포스 창 없음, 대기중...")
                time.sleep(cfg["poll_interval_sec"])
                continue

            hwnd = windows[0][0]

            # 배달 탭 클릭 → 텍스트 읽기
            if cfg.get("delivery_tab_x", 0) != 0:
                click_delivery_tab(cfg, hwnd)

            texts = get_child_texts(hwnd)
            title = get_window_text(hwnd)
            all_texts = [title] + texts

            count, matched = extract_order_count(all_texts)
            if count is not None:
                if count != last_count:
                    print(f"[{time.strftime('%H:%M:%S')}] 주문: {count}건 (변경: {last_count}→{count})")
                    ok = update_gist(cfg, count)
                    if ok:
                        print(f"[{time.strftime('%H:%M:%S')}] Gist 업데이트 완료")
                    last_count = count

            time.sleep(cfg["poll_interval_sec"])

        except KeyboardInterrupt:
            print("\n모니터링 종료")
            break
        except Exception as e:
            print(f"[!] 오류: {e}")
            time.sleep(cfg["poll_interval_sec"])


if __name__ == "__main__":
    main()
