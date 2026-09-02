# 사내 표준 폼(LFP 지게차 .pptx)에서 표지 · 목차 · 본문 레이아웃만 남긴 base.pptx 를 만든다.
#   python3 make_base.py <사내폼.pptx> [본문 장 수]
# 원본은 사외비라 저장소에 두지 않는다 — 로컬 경로로 넘긴다.
import re, shutil, subprocess, sys, zipfile
from pathlib import Path

SRC = Path(sys.argv[1]).resolve()
N_BODY = int(sys.argv[2]) if len(sys.argv) > 2 else 7
HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent.parent.parent / ".claude" / "skills" / "pptx" / "scripts"
WORK, OUT = HERE / "_work", HERE / "base.pptx"

shutil.rmtree(WORK, ignore_errors=True)
zipfile.ZipFile(SRC).extractall(WORK)

# 본문 템플릿(slide4 — 제목+①/→ 목록 레이아웃)을 필요한 수만큼 복제한다.
made = []
for _ in range(N_BODY - 1):
    out = subprocess.run([sys.executable, str(SCRIPTS / "add_slide.py"), str(WORK), "slide4.xml",
                          "--after", "slide4.xml"], capture_output=True, text=True, check=True)
    made.append(re.search(r"Created ppt/(slides/slide\d+\.xml)", out.stdout).group(1))

pres = (WORK / "ppt" / "presentation.xml").read_text(encoding="utf-8")
rels = (WORK / "ppt" / "_rels" / "presentation.xml.rels").read_text(encoding="utf-8")
tgt2rid = {t: r for r, t in re.findall(r'Id="(rId\d+)"[^>]*Target="(slides/slide\d+\.xml)"', rels)}
sid = {rid: s for s, rid in re.findall(r'<p:sldId id="(\d+)" r:id="(rId\d+)"/>', pres)}

# 표지 · 목차 · 본문 템플릿 + 복제본(생성 역순이라 뒤집는다)
keep = ["slides/slide1.xml", "slides/slide2.xml", "slides/slide4.xml"] + made[::-1]
lst = "".join('<p:sldId id="%s" r:id="%s"/>' % (sid[tgt2rid[t]], tgt2rid[t]) for t in keep)
pres = re.sub(r"<p:sldIdLst>.*?</p:sldIdLst>", "<p:sldIdLst>" + lst + "</p:sldIdLst>", pres, flags=re.S)
(WORK / "ppt" / "presentation.xml").write_text(pres, encoding="utf-8")

subprocess.run([sys.executable, str(SCRIPTS / "clean.py"), str(WORK)], check=True,
               stdout=subprocess.DEVNULL)
OUT.unlink(missing_ok=True)
subprocess.run(["zip", "-qXr", str(OUT), "."], cwd=WORK, check=True)
shutil.rmtree(WORK)
print(f"base.pptx — 표지 + 목차 + 본문 {N_BODY}장")
