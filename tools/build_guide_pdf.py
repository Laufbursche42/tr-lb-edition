#!/usr/bin/env python
# Theme guide -> scientific-paper PDF via pandoc (xelatex), from the MARKDOWN source.
# Title page (abstract on it), hierarchical numbered ToC w/ page numbers, running
# header/footer from p.2, author, repo link in footer, guide version (no date).
#   usage: build_guide_pdf.py <md> <en|de> <out.pdf> <repo_url> <AppName> <guide_version> <author>
import sys, pathlib, subprocess
import os

PANDOC = os.environ.get("PANDOC", "pandoc")
md_path, lang, out_pdf, repo_url, app_name, gver, author = (list(sys.argv)+[""]*7)[1:8]
md_path = pathlib.Path(md_path)
out_pdf = pathlib.Path(out_pdf); work = out_pdf.parent / "tex"; work.mkdir(parents=True, exist_ok=True)

META = {
 'en': dict(title="Custom Theme Guide", sub="Theme System Reference", toc="Contents", verlbl="Guide version", authlbl="Author",
   abstract="Custom themes replace the fold area (the top section of the main dashboard) with your own HTML layout. You supply HTML for the structure and CSS for the look; the app fills your elements with live scooter data automatically, every 500 ms, with no JavaScript on your side. This document is the complete reference: the bundle format, every live-data hook, the live-state signals, the circular speed-gauge technique, the CSS variables, the security model and a full worked example."),
 'de': dict(title="Anleitung für eigene Themes", sub="Referenz zum Theme-System", toc="Inhalt", verlbl="Guide-Version", authlbl="Autor",
   abstract="Eigene Themes ersetzen den Fold-Bereich (den oberen Teil des Haupt-Dashboards) durch dein eigenes HTML-Layout. Du lieferst HTML für die Struktur und CSS für die Optik; die App füllt deine Elemente automatisch alle 500 ms mit Live-Scooter-Daten, ganz ohne JavaScript auf deiner Seite. Dieses Dokument ist die vollständige Referenz: das Bundle-Format, jeder Live-Daten-Hook, die Live-Zustandssignale, die Tacho-Technik, die CSS-Variablen, das Sicherheitsmodell und ein vollständiges Beispiel."),
}[lang]
M = META

# --- source = markdown; keep only the body (drop H1 title, intro, manual ToC) -
lines = md_path.read_text(encoding="utf-8").splitlines()
TOC = ('Table of contents', 'Inhaltsverzeichnis', 'Inhalt')
start = 0
for i, l in enumerate(lines):
    if l.startswith('## ') and not any(t in l for t in TOC):
        start = i; break
section_src = work / f"section_{lang}.md"
section_src.write_text('\n'.join(lines[start:]), encoding="utf-8")

repolbl = (repo_url.replace("https://","").replace("http://","")).replace('_',r'\_')
ABSTRACTLBL = {'en':'Abstract', 'de':'Kurzfassung'}[lang]

# --- preamble: fancyhdr + a one-page custom title page (abstract on it) -------
preamble = work / f"preamble_{lang}.tex"
preamble.write_text(r"""
\usepackage{fancyhdr}
\usepackage{lastpage}
\setlength{\headheight}{14pt}
\pagestyle{fancy}
\fancyhf{}
\fancyhead[L]{\footnotesize """ + M['title'] + r"""}
\fancyhead[R]{\footnotesize\textsc{""" + app_name + r"""}}
\fancyfoot[L]{\footnotesize\href{""" + repo_url + r"""}{""" + repolbl + r"""}}
\fancyfoot[R]{\footnotesize\thepage\ / \pageref{LastPage}}
\renewcommand{\headrulewidth}{0.4pt}
\renewcommand{\footrulewidth}{0.4pt}
\fancypagestyle{plain}{\fancyhf{}\renewcommand{\headrulewidth}{0pt}\renewcommand{\footrulewidth}{0pt}}
\usepackage{titlesec}
\titlespacing*{\section}{0pt}{12pt}{4pt}
% --- custom single-page title page (title + abstract together) ---
\AtBeginDocument{%
\begin{titlepage}\centering
\vspace*{2.3cm}
{\scshape\large """ + app_name + r"""}\par\vspace{2pt}
{\scshape """ + M['sub'] + r"""}\par
\vspace{1.6cm}
\rule{\linewidth}{0.4pt}\par\vspace{10pt}
{\huge\bfseries """ + M['title'] + r"""\par}
\vspace{10pt}\rule{\linewidth}{0.4pt}\par
\vspace{1.5cm}
\begin{minipage}{0.84\linewidth}
  \begin{center}{\scshape\footnotesize """ + ABSTRACTLBL + r"""}\end{center}
  \vspace{3pt}\small\noindent """ + M['abstract'] + r"""
\end{minipage}\par
\vspace{1.8cm}
{\renewcommand{\arraystretch}{1.3}\normalsize
\begin{tabular}{r@{\hskip 10pt}l}
\textsc{""" + M['authlbl'] + r"""} & """ + author + r""" \\
\textsc{""" + M['verlbl'] + r"""} & """ + gver + r""" \\
\textsc{Repository} & \href{""" + repo_url + r"""}{""" + repolbl + r"""} \\
\end{tabular}}
\vfill
\end{titlepage}%
}
""", encoding="utf-8")

cmd = [PANDOC, str(section_src), "-f", "gfm",
       "--include-in-header", str(preamble),
       "--pdf-engine=xelatex",
       "--toc", "--toc-depth=3", "--number-sections", "--shift-heading-level-by=-1",
       "-V", "documentclass=article",
       "-V", "fontsize=11pt",
       "-V", "geometry:margin=2.3cm",
       "-V", f"lang={lang}",
       "-V", "colorlinks=true", "-V", "linkcolor=[RGB]{20,56,122}",
       "-V", "urlcolor=[RGB]{20,56,122}", "-V", "toccolor=black",
       "-V", f"toc-title={M['toc']}",
       "-o", str(out_pdf)]
r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
if out_pdf.exists() and r.returncode == 0:
    print("OK", out_pdf, out_pdf.stat().st_size, "bytes", "lang=", lang)
else:
    print("FAIL rc=", r.returncode)
    print((r.stderr or "")[-2500:])
    sys.exit(1)
