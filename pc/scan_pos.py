"""
POS 창 UI 구조 자동 스캔 → 결과 파일 저장
실행: python scan_pos.py
결과: scan_result.txt (같은 폴더)
"""
from pywinauto import Application, Desktop, findwindows
import time

OUTPUT = "scan_result.txt"
lines = []

def log(msg):
    print(msg)
    lines.append(msg)

def save():
    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"\n=== 결과 저장: {OUTPUT} ===")

log("=" * 60)
log("  POS 창 UI 스캔 결과")
log(f"  시간: {time.strftime('%Y-%m-%d %H:%M:%S')}")
log("=" * 60)

# 1. 모든 창 목록
log("\n[1] 열린 창 목록:")
all_wins = findwindows.find_elements()
for w in all_wins:
    if w.name.strip():
        log(f"  - \"{w.name}\"")

# 2. GENESIS 또는 POS 관련 창 찾기
keywords = ["GENESIS", "genesis", "BBQ", "bbq", "MATE", "mate", "POS", "pos"]
target_win = None
target_title = ""

for kw in keywords:
    try:
        app = Application(backend="uia").connect(title_re=f".*{kw}.*", timeout=3)
        target_win = app.window(title_re=f".*{kw}.*")
        target_title = target_win.window_text()
        log(f"\n[2] 연결 성공: \"{target_title}\" (키워드: {kw}, 백엔드: uia)")
        break
    except Exception:
        pass
    try:
        app = Application(backend="win32").connect(title_re=f".*{kw}.*", timeout=3)
        target_win = app.window(title_re=f".*{kw}.*")
        target_title = target_win.window_text()
        log(f"\n[2] 연결 성공: \"{target_title}\" (키워드: {kw}, 백엔드: win32)")
        break
    except Exception:
        pass

if not target_win:
    log("\n[!] POS 관련 창을 찾지 못했습니다.")
    log("    위 창 목록에서 POS 프로그램 이름을 확인해주세요.")
    save()
    input("엔터를 누르면 종료...")
    exit()

# 3. UI 트리 전체 덤프
log(f"\n[3] UI 트리 덤프 (\"{target_title}\"):")
log("-" * 60)

count = 0
try:
    for child in target_win.descendants():
        try:
            text = child.window_text() or ""
            ctrl_type = child.friendly_class_name()
            auto_id = ""
            try:
                auto_id = child.automation_id()
            except Exception:
                pass
            rect_str = ""
            try:
                r = child.rectangle()
                rect_str = f"({r.left},{r.top})({r.width()}x{r.height()})"
            except Exception:
                pass

            if text.strip() or auto_id:
                log(f"  [{ctrl_type}] text=\"{text}\"  id=\"{auto_id}\"  {rect_str}")
                count += 1
        except Exception:
            continue
except Exception as e:
    log(f"  [ERR] 트리 스캔 오류: {e}")

log(f"\n총 {count}개 요소")

# 4. 텍스트만 모아서 표시
log(f"\n[4] 텍스트 전체 목록:")
log("-" * 60)
texts = set()
try:
    texts.add(target_win.window_text())
    for child in target_win.descendants():
        try:
            t = child.window_text()
            if t and t.strip():
                texts.add(t.strip())
        except Exception:
            continue
except Exception:
    pass

for t in sorted(texts):
    log(f"  [{t}]")
log(f"\n고유 텍스트 {len(texts)}개")

# 5. "배달" 관련 요소 상세
log(f"\n[5] '배달' 포함 요소:")
log("-" * 60)
found = False
try:
    for child in target_win.descendants():
        try:
            text = child.window_text() or ""
            auto_id = ""
            try:
                auto_id = child.automation_id()
            except Exception:
                pass
            if "배달" in text or "배달" in auto_id:
                ctrl_type = child.friendly_class_name()
                rect_str = ""
                try:
                    r = child.rectangle()
                    rect_str = f"pos=({r.left},{r.top}) size=({r.width()}x{r.height()})"
                except Exception:
                    pass
                log(f"  [{ctrl_type}] text=\"{text}\" id=\"{auto_id}\" {rect_str}")
                found = True
        except Exception:
            continue
except Exception:
    pass
if not found:
    log("  (없음)")

save()
input("\n엔터를 누르면 종료...")
