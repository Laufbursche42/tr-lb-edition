#!/usr/bin/env python
# Build the in-app theme-guide.html (both languages) from the markdown sources.
# Reproduces the app format: tg-lang wrappers, tg-ai-box (markdown + PDF links),
# tg-toc, tg-table / tg-rule / tg-note classes, tg-{lang}-{slug} heading ids.
#   build_guide_html.py <out_html> <repo_url> 'en|<md>|<md_url>|<pdf_url>' 'de|...'
#
# Single source of truth = the markdown. The HTML is generated, never hand-edited.
import sys, re, os, subprocess, pathlib
from bs4 import BeautifulSoup, NavigableString

PANDOC = os.environ.get("PANDOC", "pandoc")
out_html = pathlib.Path(sys.argv[1])
repo_url = sys.argv[2]
specs = sys.argv[3:]  # one per language: "lang|md_path|md_url|pdf_url"

AIBOX = {
 'en': ("&#128203; <strong>Building a theme with AI?</strong> Give your AI assistant the raw link to this Markdown - it has everything needed to produce a valid, importable theme:",
        "&#128196; <strong>Prefer a readable PDF?</strong> The same guide as a formatted document:"),
 'de': ("&#128203; <strong>Theme mit KI bauen?</strong> Gib deinem KI-Assistenten den Raw-Link zu dieser Markdown - sie enth&auml;lt alles f&uuml;r ein g&uuml;ltiges, importierbares Theme:",
        "&#128196; <strong>Lieber ein lesbares PDF?</strong> Derselbe Guide als formatiertes Dokument:"),
}
TOC_TITLE = {'en': 'Contents', 'de': 'Inhalt'}
TOC_SKIP  = ('Table of contents', 'Inhaltsverzeichnis', 'Inhalt')

def slugify(t):
    t = re.sub(r'<[^>]+>', '', t)
    t = t.replace('&lt;','').replace('&gt;','').replace('&amp;','')
    t = re.sub(r'[^\w\s-]', '', t.lower())
    return re.sub(r'[\s_]+', '-', t).strip('-')

def fragment(lang, md_path, md_url, pdf_url):
    lines = pathlib.Path(md_path).read_text(encoding='utf-8').splitlines()
    start = 0
    for i, l in enumerate(lines):
        if l.startswith('## ') and not any(t in l for t in TOC_SKIP):
            start = i; break
    body_md = '\n'.join(lines[start:])
    r = subprocess.run([PANDOC, '-f', 'gfm', '-t', 'html', '--no-highlight', '--wrap=none'],
                       input=body_md, capture_output=True, text=True, encoding='utf-8')
    soup = BeautifulSoup(r.stdout, 'html.parser')

    # heading ids -> tg-{lang}-{slug}; collect for ToC
    toc = []  # (level, id, text)
    seen = set()
    for h in soup.find_all(['h2', 'h3', 'h4']):
        base = slugify(h.decode_contents())
        sid = f'tg-{lang}-{base}'; n = sid
        k = 2
        while n in seen: n = f'{sid}-{k}'; k += 1
        seen.add(n); h['id'] = n
        if h.name in ('h2', 'h3'):
            toc.append((h.name, n, h.decode_contents()))
    for t in soup.find_all('table'):
        cls = ['tg-table']
        th = t.find('th')
        if th and th.get_text(strip=True) in ('Key', 'Schlüssel'): cls.append('tg-table-keys')
        t['class'] = cls
    for hr in soup.find_all('hr'): hr['class'] = hr.get('class', []) + ['tg-rule']
    # blockquotes -> p.tg-note
    for bq in soup.find_all('blockquote'):
        p = soup.new_tag('p'); p['class'] = ['tg-note']
        inner = ''.join(str(c) for c in bq.find_all('p'))
        p.append(BeautifulSoup(inner or bq.decode_contents(), 'html.parser'))
        bq.replace_with(p)

    # ToC html (hierarchical)
    toc_html = [f'<div class="tg-toc">',
                f'<p class="tg-toc-title" data-i18n="theme.guide.toc">{TOC_TITLE[lang]}</p>', '<ul>']
    open_sub = False
    for level, sid, text in toc:
        if level == 'h2':
            if open_sub: toc_html.append('</ul></li>'); open_sub = False
            toc_html.append(f'<li><a href="#{sid}">{text}</a>')
            toc_html.append('</li>')  # closed unless a sub follows
        else:  # h3
            if toc_html[-1] == '</li>':
                toc_html[-1] = ''  # reopen the parent li for nesting
                toc_html.append('<ul>'); open_sub = True
            elif not open_sub:
                toc_html.append('<ul>'); open_sub = True
            toc_html.append(f'<li><a href="#{sid}">{text}</a></li>')
    if open_sub: toc_html.append('</ul></li>')
    toc_html.append('</ul></div>')

    ai1, ai2 = AIBOX[lang]
    # Real tappable links (class tg-ai-link). The guide's click handler routes
    # external hrefs through openExt -> the system browser, since the WebView
    # itself cancels any non-file:// navigation.
    aibox = (f'<div class="tg-ai-box">\n<p>{ai1}</p>\n'
             f'<a class="tg-ai-link" href="{md_url}">{md_url}</a>\n'
             f'<p>{ai2}</p>\n'
             f'<a class="tg-ai-link" href="{pdf_url}">{pdf_url}</a>\n</div>')

    return (f'<div class="tg-lang tg-{lang}">\n\n{aibox}\n\n'
            f'{"".join(toc_html)}\n\n<hr class="tg-rule">\n\n'
            f'{soup.decode_contents()}\n</div>')

parts = []
for spec in specs:
    lang, md_path, md_url, pdf_url = spec.split('|')
    parts.append(f'<!-- {lang.upper()} -->\n' + fragment(lang, md_path, md_url, pdf_url))
out_html.write_text('\n\n'.join(parts) + '\n', encoding='utf-8')
print("wrote", out_html, out_html.stat().st_size, "bytes")
