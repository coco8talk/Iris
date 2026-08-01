#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import html
import re
import sys
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString, Tag
from markdown_it import MarkdownIt


SOURCE_OUTPUTS = (
    ("00-execution-plan.md", "00-execution-plan.html"),
    ("D4-architecture-review.md", "d4-architecture-review.html"),
    ("M1.md", "m1-learning-guide.html"),
    ("M2-工具层契约.md", "m2-learning-guide.html"),
    ("M3-第一个StateGraph.md", "m3-learning-guide.html"),
    ("M4-状态对象与记忆.md", "m4-learning-guide.html"),
    ("M5-webhook入口与去重.md", "m5-learning-guide.html"),
    ("M6-分诊triage.md", "m6-learning-guide.html"),
    ("M7-排查与证据台账.md", "m7-learning-guide.html"),
    ("M8-报告与出口.md", "m8-learning-guide.html"),
    ("M9-verify跨家族验收.md", "m9-learning-guide.html"),
    ("M10-多Agent.md", "m10-learning-guide.html"),
    ("M11-Skills与知识库.md", "m11-learning-guide.html"),
    ("M12-修复审批与评估.md", "m12-learning-guide.html"),
)

HERE = Path(__file__).resolve().parent
LEARNING_ROOT = HERE.parent
REFERENCE_PATH = HERE / "m0-learning-guide.html"

TABLE_STYLE = """

    .table-wrap {
      margin: 20px 0 22px;
      overflow-x: auto;
      border: 1px solid var(--border);
      border-radius: 14px;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      background: color-mix(in oklch, var(--surface) 76%, transparent);
      font-size: 0.92em;
    }

    th, td {
      padding: 12px 14px;
      border-bottom: 1px solid var(--border);
      text-align: left;
      vertical-align: top;
    }

    th {
      color: var(--fg);
      background: color-mix(in oklch, var(--accent) 14%, white);
      font-weight: 600;
    }

    tr:last-child td {
      border-bottom: 0;
    }
"""

PAGE_TEMPLATE = """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <meta name="source-sha256" content="@@SOURCE_SHA256@@">
  <title>@@TITLE@@</title>
  <style>@@STYLE@@</style>
</head>
<body>
  <div class="ambient ambient-one" aria-hidden="true"></div>
  <div class="ambient ambient-two" aria-hidden="true"></div>
  <div class="ambient ambient-three" aria-hidden="true"></div>

  <main class="page-shell" data-od-id="page-shell">
    <div class="reader-layout">
      <article class="document" id="document-content" data-od-id="document-content">
@@ARTICLE@@
      </article>
      <aside class="toc" data-od-id="table-of-contents">
        <details open>
          <summary aria-label="展开或收起目录">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 7h16M4 12h16M4 17h16"></path>
            </svg>
          </summary>
          <ol class="toc-list" id="toc-list">
@@TOC@@
          </ol>
        </details>
      </aside>
    </div>
  </main>

  <script id="source-markdown" type="text/plain" data-encoding="base64">@@SOURCE_BASE64@@</script>
  <script src="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.11.1/build/highlight.min.js"></script>
  <script>
    (function () {
      "use strict";

      var article = document.getElementById("document-content");
      var tocList = document.getElementById("toc-list");

      article.querySelectorAll(".completion-checkbox").forEach(function (checkbox) {
        var storageKey = checkbox.getAttribute("data-check-key");
        try {
          checkbox.checked = localStorage.getItem(storageKey) === "true";
        } catch (_) {}
        checkbox.addEventListener("change", function () {
          try {
            localStorage.setItem(storageKey, String(checkbox.checked));
          } catch (_) {}
        });
      });

      article.querySelectorAll(".copy-code").forEach(function (button) {
        button.addEventListener("click", function () {
          var code = button.closest(".code-shell").querySelector("code").textContent;
          navigator.clipboard.writeText(code).then(function () {
            button.innerHTML =
              '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"></path></svg>';
            window.setTimeout(function () {
              button.innerHTML =
                '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2"></rect><path d="M15 9V7a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"></path></svg>';
            }, 1400);
          });
        });
      });

      if (window.hljs) {
        article.querySelectorAll("pre code").forEach(function (block) {
          window.hljs.highlightElement(block);
        });
      }

      var revealObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            revealObserver.unobserve(entry.target);
          }
        });
      }, { threshold: 0.08 });

      article.querySelectorAll(".reveal").forEach(function (node) {
        revealObserver.observe(node);
      });

      var tocLinks = Array.from(tocList.querySelectorAll("a"));
      var sectionObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          tocLinks.forEach(function (link) {
            var current = link.getAttribute("href") === "#" + entry.target.id;
            if (current) link.setAttribute("aria-current", "true");
            else link.removeAttribute("aria-current");
          });
        });
      }, { rootMargin: "-18% 0px -68% 0px", threshold: 0 });

      tocLinks.forEach(function (link) {
        var section = document.querySelector(link.getAttribute("href"));
        if (section) sectionObserver.observe(section);
      });
    }());
  </script>
</body>
</html>
"""


def markdown_renderer() -> MarkdownIt:
    return MarkdownIt("commonmark", {"html": False}).enable("table")


def slug(value: str) -> str:
    normalized = "-".join(
        part
        for part in re.split(
            r"-+",
            "".join(
                character.lower() if character.isalnum() else "-"
                for character in value
            ),
        )
        if part
    )
    return f"section-{normalized[:48]}" if normalized else "section"


def unique_slug(value: str, used_ids: set[str], suffix: str = "") -> str:
    candidate = slug(value)
    if suffix:
        candidate = f"{candidate}-{suffix}"
    base = candidate
    sequence = 2
    while candidate in used_ids:
        candidate = f"{base}-{sequence}"
        sequence += 1
    used_ids.add(candidate)
    return candidate


def first_text_with_task_marker(item: Tag) -> NavigableString | None:
    for text_node in item.find_all(string=True):
        if re.match(r"^\s*\[[ xX]\]\s+", str(text_node)):
            return text_node
        if str(text_node).strip():
            return None
    return None


def transform_task_lists(soup: BeautifulSoup, page_key: str) -> None:
    checklist_index = 0
    for unordered_list in list(soup.find_all("ul")):
        direct_items = unordered_list.find_all("li", recursive=False)
        markers = [first_text_with_task_marker(item) for item in direct_items]
        if not direct_items or any(marker is None for marker in markers):
            continue

        unordered_list["class"] = [*unordered_list.get("class", []), "checklist"]
        for item_index, (item, marker) in enumerate(zip(direct_items, markers)):
            marker_text = str(marker)
            initially_checked = marker_text.lstrip().lower().startswith("[x]")
            marker.replace_with(re.sub(r"^\s*\[[ xX]\]\s+", "", marker_text, count=1))

            original_contents = list(item.contents)
            item.clear()
            label = soup.new_tag("label")
            label["class"] = ["completion-label"]
            checkbox = soup.new_tag("input")
            checkbox["class"] = ["completion-checkbox"]
            checkbox["type"] = "checkbox"
            checkbox["data-check-key"] = (
                f"{page_key}-check-{checklist_index}-{item_index}"
            )
            if initially_checked:
                checkbox["checked"] = ""
            check_box = soup.new_tag("span")
            check_box["class"] = ["check-box"]
            check_box["aria-hidden"] = "true"
            content = soup.new_tag("span")
            for child in original_contents:
                content.append(child)
            label.extend([checkbox, check_box, content])
            item.append(label)
        checklist_index += 1


def transform_steps(soup: BeautifulSoup) -> None:
    for label in soup.find_all(["h3", "p"]):
        if label.get_text(strip=True) != "落笔顺序":
            continue
        ordered_list = label.find_next_sibling()
        if not isinstance(ordered_list, Tag) or ordered_list.name != "ol":
            continue
        ordered_list["class"] = [*ordered_list.get("class", []), "steps"]
        start = int(ordered_list.get("start", 1))
        for offset, item in enumerate(ordered_list.find_all("li", recursive=False)):
            original_contents = list(item.contents)
            item.clear()
            badge = soup.new_tag("span")
            badge["class"] = ["step-badge"]
            badge["aria-hidden"] = "true"
            badge.string = str(start + offset)
            card = soup.new_tag("div")
            card["class"] = ["step-card"]
            for child in original_contents:
                card.append(child)
            item.extend([badge, card])


def transform_code_blocks(soup: BeautifulSoup) -> None:
    for code_index, preformatted in enumerate(list(soup.find_all("pre")), start=1):
        code = preformatted.find("code")
        language = ""
        if code:
            for class_name in code.get("class", []):
                if class_name.startswith("language-"):
                    language = class_name.removeprefix("language-")
                    break

        wrapper = soup.new_tag("div")
        wrapper["class"] = ["code-shell"]
        wrapper["data-od-id"] = f"code-{code_index}"
        preformatted.wrap(wrapper)

        toolbar = soup.new_tag("div")
        toolbar["class"] = ["code-toolbar"]
        language_label = soup.new_tag("span")
        language_label["class"] = ["language-label"]
        language_label.string = language
        button_fragment = BeautifulSoup(
            '<button class="copy-code" type="button" aria-label="复制代码">'
            '<svg viewBox="0 0 24 24" aria-hidden="true">'
            '<rect x="9" y="9" width="10" height="10" rx="2"></rect>'
            '<path d="M15 9V7a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v6a2 '
            '2 0 0 0 2 2h2"></path></svg></button>',
            "html.parser",
        )
        toolbar.append(language_label)
        toolbar.append(button_fragment.button)
        wrapper.insert(0, toolbar)


def transform_tables(soup: BeautifulSoup) -> None:
    for table in list(soup.find_all("table")):
        wrapper = soup.new_tag("div")
        wrapper["class"] = ["table-wrap"]
        table.wrap(wrapper)


def linkify_plain_urls(soup: BeautifulSoup) -> None:
    url_pattern = re.compile(r"https?://[^\s<）。，；]+")
    for text_node in list(soup.find_all(string=url_pattern)):
        if text_node.parent and text_node.parent.name in {
            "a",
            "code",
            "pre",
            "script",
            "style",
        }:
            continue
        text_value = str(text_node)
        replacements: list[Tag | NavigableString] = []
        cursor = 0
        for match in url_pattern.finditer(text_value):
            if match.start() > cursor:
                replacements.append(NavigableString(text_value[cursor : match.start()]))
            link = soup.new_tag("a", href=match.group(0))
            link["target"] = "_blank"
            link["rel"] = ["noreferrer"]
            link.string = match.group(0)
            replacements.append(link)
            cursor = match.end()
        if cursor < len(text_value):
            replacements.append(NavigableString(text_value[cursor:]))
        text_node.replace_with(*replacements)


def transform_special_paragraphs(soup: BeautifulSoup) -> None:
    for paragraph in list(soup.find_all("p")):
        first_content = next(
            (
                child
                for child in paragraph.contents
                if not isinstance(child, NavigableString) or str(child).strip()
            ),
            None,
        )
        if (
            isinstance(first_content, Tag)
            and first_content.name == "strong"
            and first_content.get_text(strip=True) == "id"
        ):
            wrapper = soup.new_tag("div")
            wrapper["class"] = ["meta-strip"]
            paragraph.wrap(wrapper)

        emphasized = paragraph.find("em", recursive=False)
        if (
            emphasized
            and emphasized.get_text(strip=True).startswith("版本 C 特征")
        ):
            paragraph["class"] = [
                *paragraph.get("class", []),
                "closing-note",
            ]


def assign_heading_ids(soup: BeautifulSoup) -> list[tuple[str, str]]:
    used_ids: set[str] = set()
    toc: list[tuple[str, str]] = []
    for index, heading in enumerate(soup.find_all(["h2", "h3"]), start=1):
        heading_text = heading.get_text(" ", strip=True)
        if heading.name == "h2":
            heading_id = unique_slug(heading_text, used_ids)
            toc.append((heading_id, heading_text))
        else:
            heading_id = unique_slug(heading_text, used_ids, str(index))
        heading["id"] = heading_id
        heading["data-od-id"] = heading_id
    return toc


def wrap_content_cards(soup: BeautifulSoup) -> str:
    html_parts: list[str] = []
    section_open = False
    for node in list(soup.contents):
        if isinstance(node, NavigableString) and not str(node).strip():
            continue
        if not isinstance(node, Tag):
            html_parts.append(str(node))
            continue
        if node.name == "h1":
            if section_open:
                html_parts.append("</section>")
                section_open = False
            html_parts.append(
                '<header class="glass-card hero reveal" '
                'data-od-id="document-title">'
                f"{node}</header>"
            )
            continue
        if node.name == "h2":
            if section_open:
                html_parts.append("</section>")
            section_id = node["id"]
            del node["id"]
            html_parts.append(
                f'<section class="glass-card reveal" id="{section_id}" '
                f'data-od-id="{section_id}">{node}'
            )
            section_open = True
            continue
        if node.name == "hr":
            continue
        html_parts.append(str(node))
    if section_open:
        html_parts.append("</section>")
    return "\n".join(html_parts)


def render_document(
    markdown_text: str,
    page_key: str,
) -> tuple[str, list[tuple[str, str]]]:
    rendered = markdown_renderer().render(markdown_text)
    soup = BeautifulSoup(rendered, "html.parser")
    toc = assign_heading_ids(soup)
    transform_task_lists(soup, page_key)
    transform_steps(soup)
    transform_code_blocks(soup)
    transform_tables(soup)
    linkify_plain_urls(soup)
    transform_special_paragraphs(soup)
    for link in soup.find_all("a", href=re.compile(r"^https?://")):
        link["target"] = "_blank"
        link["rel"] = ["noreferrer"]
    return wrap_content_cards(soup), toc


def extract_reference_style(reference_path: Path) -> str:
    reference_soup = BeautifulSoup(
        reference_path.read_text(encoding="utf-8"),
        "html.parser",
    )
    style = reference_soup.select_one("style")
    if style is None or style.string is None:
        raise ValueError(f"Reference page has no readable style block: {reference_path}")
    return str(style.string)


def extract_document_title(markdown_text: str) -> str:
    tokens = markdown_renderer().parse(markdown_text)
    titles = [
        tokens[index + 1].content
        for index, token in enumerate(tokens)
        if token.type == "heading_open" and token.tag == "h1"
    ]
    if len(titles) != 1:
        raise ValueError(f"Expected exactly one H1, found {len(titles)}")
    rendered_title = markdown_renderer().renderInline(titles[0])
    return BeautifulSoup(rendered_title, "html.parser").get_text()


def build_page(
    source_path: Path,
    output_path: Path,
    reference_path: Path = REFERENCE_PATH,
) -> None:
    source_bytes = source_path.read_bytes()
    markdown_text = source_bytes.decode("utf-8")
    if sum(
        1
        for line in markdown_text.splitlines()
        if line.startswith("```")
    ) % 2:
        raise ValueError(f"Unclosed fenced code block: {source_path}")

    title = extract_document_title(markdown_text)
    page_key = output_path.stem
    article_html, toc = render_document(markdown_text, page_key)
    toc_html = "\n".join(
        f'            <li><a href="#{section_id}">{html.escape(text)}</a></li>'
        for section_id, text in toc
    )
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    source_base64 = base64.b64encode(source_bytes).decode("ascii")
    reference_style = extract_reference_style(reference_path)

    page = (
        PAGE_TEMPLATE.replace("@@TITLE@@", html.escape(title))
        .replace("@@SOURCE_SHA256@@", source_sha256)
        .replace("@@STYLE@@", reference_style + TABLE_STYLE)
        .replace("@@ARTICLE@@", article_html)
        .replace("@@TOC@@", toc_html)
        .replace("@@SOURCE_BASE64@@", source_base64)
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(page, encoding="utf-8")


def token_counts(markdown_text: str) -> dict[str, int]:
    counts = {
        "h1": 0,
        "h2": 0,
        "h3": 0,
        "table": 0,
        "code": 0,
        "blockquote": 0,
        "ul": 0,
        "ol": 0,
    }
    for token in markdown_renderer().parse(markdown_text):
        if token.type == "heading_open" and token.tag in {"h1", "h2", "h3"}:
            counts[token.tag] += 1
        elif token.type == "table_open":
            counts["table"] += 1
        elif token.type in {"fence", "code_block"}:
            counts["code"] += 1
        elif token.type == "blockquote_open":
            counts["blockquote"] += 1
        elif token.type == "bullet_list_open":
            counts["ul"] += 1
        elif token.type == "ordered_list_open":
            counts["ol"] += 1
    return counts


def verify_page(
    source_path: Path,
    output_path: Path,
    reference_path: Path = REFERENCE_PATH,
) -> list[str]:
    errors: list[str] = []
    if not output_path.exists():
        return [f"missing output: {output_path.name}"]

    source_bytes = source_path.read_bytes()
    markdown_text = source_bytes.decode("utf-8")
    soup = BeautifulSoup(output_path.read_text(encoding="utf-8"), "html.parser")
    source_node = soup.select_one("#source-markdown")
    digest_node = soup.select_one('meta[name="source-sha256"]')

    if source_node is None:
        errors.append("missing embedded source")
    else:
        try:
            embedded_bytes = base64.b64decode(
                source_node.get_text(strip=True),
                validate=True,
            )
        except ValueError as error:
            errors.append(f"invalid source Base64: {error}")
        else:
            if embedded_bytes != source_bytes:
                errors.append("embedded source bytes differ")

    expected_digest = hashlib.sha256(source_bytes).hexdigest()
    if digest_node is None or digest_node.get("content") != expected_digest:
        errors.append("source SHA-256 differs")

    reference_style = extract_reference_style(reference_path)
    generated_style = soup.select_one("style")
    if (
        generated_style is None
        or generated_style.string is None
        or not str(generated_style.string).startswith(reference_style)
    ):
        errors.append("M0 reference style is not preserved verbatim")

    article = soup.select_one("#document-content")
    if article is None:
        errors.append("missing document article")
        return errors

    expected_counts = token_counts(markdown_text)
    actual_counts = {
        "h1": len(article.select("h1")),
        "h2": len(article.select("h2")),
        "h3": len(article.select("h3")),
        "table": len(article.select("table")),
        "code": len(article.select(".code-shell")),
        "blockquote": len(article.select("blockquote")),
        "ul": len(article.select("ul")),
        "ol": len(article.select("ol")),
    }
    for structure, expected_count in expected_counts.items():
        if actual_counts[structure] != expected_count:
            errors.append(
                f"{structure} count differs: "
                f"expected {expected_count}, found {actual_counts[structure]}"
            )

    h1 = article.select_one("h1")
    expected_title = extract_document_title(markdown_text)
    if h1 is None or h1.get_text() != expected_title:
        errors.append("rendered H1 differs")

    ids = [tag["id"] for tag in soup.select("[id]")]
    if len(ids) != len(set(ids)):
        errors.append("duplicate element IDs")
    for toc_link in soup.select("#toc-list a"):
        target = toc_link.get("href", "")
        if not target.startswith("#") or soup.select_one(target) is None:
            errors.append(f"broken TOC target: {target}")

    task_item_count = len(
        re.findall(r"^- \[[ xX]\] ", markdown_text, flags=re.MULTILINE)
    )
    if len(article.select(".completion-checkbox")) != task_item_count:
        errors.append(
            "task item count differs: "
            f"expected {task_item_count}, "
            f"found {len(article.select('.completion-checkbox'))}"
        )
    return errors


def run(verify_only: bool) -> int:
    reference_digest = hashlib.sha256(REFERENCE_PATH.read_bytes()).hexdigest()
    failures: list[str] = []

    if not verify_only:
        for source_name, output_name in SOURCE_OUTPUTS:
            build_page(
                LEARNING_ROOT / source_name,
                HERE / output_name,
                REFERENCE_PATH,
            )

    for source_name, output_name in SOURCE_OUTPUTS:
        page_errors = verify_page(
            LEARNING_ROOT / source_name,
            HERE / output_name,
            REFERENCE_PATH,
        )
        failures.extend(f"{output_name}: {error}" for error in page_errors)

    if hashlib.sha256(REFERENCE_PATH.read_bytes()).hexdigest() != reference_digest:
        failures.append("m0-learning-guide.html changed during generation")

    if failures:
        print("\n".join(f"ERROR: {failure}" for failure in failures), file=sys.stderr)
        return 1

    if verify_only:
        print(f"Verified {len(SOURCE_OUTPUTS)}/{len(SOURCE_OUTPUTS)} HTML files; 0 errors.")
    else:
        print(f"Generated and verified {len(SOURCE_OUTPUTS)}/{len(SOURCE_OUTPUTS)} HTML files.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate Learning V2 HTML pages with the M0 reading design.",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="verify existing outputs without rewriting them",
    )
    arguments = parser.parse_args()
    return run(arguments.verify_only)


if __name__ == "__main__":
    raise SystemExit(main())
