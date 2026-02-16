"""
메이트포스 주문 건수 모니터 → GitHub Gist로 공유
PosDelay 폰 앱에서 읽어서 광고 자동 제어에 활용

사용법:
  1. Python 설치 (python.org → Add to PATH 체크)
  2. cmd에서: pip install pywin32 requests
  3. 이 파일 실행: python mate_monitor.py

처음 실행 시 메이트포스 창 목록이 표시됩니다.
올바른 창 이름을 config.json에 설정하세요.
"""

import json
import os
import re
import sys
import time
import ctypes
import ctypes.wintypes
import requests

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")

DEFAULT_CONFIG = {
    "github_token": "",
    "gist_id": "a67e5de3271d6d0716b276dc6a8391cb",
    "window_title": "메이트",
    "poll_interval_sec": 30,
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
        # "처리중 3건", "처리중: 3", "처리중(3)", "진행중 3건" 등 패턴 매칭
        m = re.search(r"(?:처리중|진행중|접수|대기)[\s:：()（）]*(\d+)", text)
        if m:
            return int(m.group(1)), text
        # 단순 숫자만 있는 경우 (창 제목에 건수 표시)
        m = re.search(r"(\d+)\s*건", text)
        if m:
            return int(m.group(1)), text
    return None, None


def update_gist(cfg, count):
    """GitHub Gist에 주문 건수 업데이트"""
    gist_id = cfg["gist_id"]
    token = cfg["github_token"]
    if not token:
        print("[!] github_token이 설정되지 않았습니다. config.json을 확인하세요.")
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
            print(f"[!] Gist 업데이트 실패: {resp.status_code} {resp.text[:100]}")
            return False
    except Exception as e:
        print(f"[!] 네트워크 오류: {e}")
        return False


def list_all_windows():
    """디버깅: 모든 보이는 창 목록 출력"""
    windows = []

    def callback(hwnd, _):
        if IsWindowVisible(hwnd):
            title = get_window_text(hwnd)
            if title.strip():
                windows.append(title)
        return True

    EnumWindows(WNDENUMPROC(callback), 0)
    return windows


def setup_token(cfg):
    """GitHub 토큰 설정 안내"""
    print("\n=== GitHub Personal Access Token 설정 ===")
    print("1. https://github.com/settings/tokens/new 접속")
    print("2. Note: PosDelay")
    print("3. Expiration: No expiration")
    print("4. 체크: gist")
    print("5. Generate token → 복사")
    print()
    token = input("토큰 입력: ").strip()
    if token:
        cfg["github_token"] = token
        save_config(cfg)
        print("[OK] 토큰 저장 완료")
    return cfg


def main():
    print("=" * 50)
    print("  메이트포스 주문 모니터 (PosDelay PC)")
    print("=" * 50)

    cfg = load_config()

    # 토큰 확인
    if not cfg["github_token"]:
        cfg = setup_token(cfg)
        if not cfg["github_token"]:
            print("[!] 토큰 없이는 실행할 수 없습니다.")
            return

    # 첫 실행: 창 목록 표시
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

    # 자식 컨트롤 텍스트 미리보기
    texts = get_child_texts(hwnd)
    if texts:
        print(f"\n창 내부 텍스트 ({len(texts)}개):")
        for t in texts[:20]:
            print(f"  [{t}]")

    count, matched = extract_order_count(texts)
    if count is not None:
        print(f"\n[OK] 주문 건수 감지: {count}건 (매칭: {matched})")
    else:
        print(f"\n[!] 주문 건수를 자동 감지하지 못했습니다.")
        print("    창 텍스트를 확인하고 패턴을 알려주세요.")

    # 모니터링 루프
    print(f"\n{cfg['poll_interval_sec']}초 간격 모니터링 시작...")
    print("종료: Ctrl+C\n")

    last_count = -1
    while True:
        try:
            # 창 다시 찾기 (재시작 대응)
            windows = find_windows(keyword)
            if not windows:
                print(f"[{time.strftime('%H:%M:%S')}] 메이트포스 창 없음, 대기중...")
                time.sleep(cfg["poll_interval_sec"])
                continue

            hwnd = windows[0][0]
            texts = get_child_texts(hwnd)
            # 창 제목도 포함
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
            else:
                # 건수 감지 못해도 주기적으로 로그
                if time.time() % 300 < cfg["poll_interval_sec"]:
                    print(f"[{time.strftime('%H:%M:%S')}] 건수 감지 못함, 텍스트: {all_texts[:5]}")

            time.sleep(cfg["poll_interval_sec"])

        except KeyboardInterrupt:
            print("\n모니터링 종료")
            break
        except Exception as e:
            print(f"[!] 오류: {e}")
            time.sleep(cfg["poll_interval_sec"])


if __name__ == "__main__":
    main()
