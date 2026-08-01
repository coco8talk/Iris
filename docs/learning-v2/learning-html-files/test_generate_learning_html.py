from __future__ import annotations

import base64
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path

from bs4 import BeautifulSoup
from markdown_it import MarkdownIt


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
GENERATOR_PATH = HERE / "generate_learning_html.py"


def markdown_headings(markdown_text: str, level: int) -> list[str]:
    tokens = MarkdownIt("commonmark").enable("table").parse(markdown_text)
    return [
        tokens[index + 1].content
        for index, token in enumerate(tokens)
        if token.type == "heading_open" and token.tag == f"h{level}"
    ]


class GeneratorManifestTests(unittest.TestCase):
    def load_generator(self):
        self.assertTrue(
            GENERATOR_PATH.exists(),
            "generate_learning_html.py must exist before it can be imported",
        )
        spec = importlib.util.spec_from_file_location(
            "generate_learning_html",
            GENERATOR_PATH,
        )
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_manifest_contains_all_fourteen_confirmed_documents(self):
        generator = self.load_generator()

        self.assertEqual(14, len(generator.SOURCE_OUTPUTS))
        self.assertEqual(
            14,
            len({output_name for _, output_name in generator.SOURCE_OUTPUTS}),
        )
        self.assertEqual(
            {
                "00-execution-plan.html",
                "d4-architecture-review.html",
                *(f"m{number}-learning-guide.html" for number in range(1, 13)),
            },
            {output_name for _, output_name in generator.SOURCE_OUTPUTS},
        )


class GeneratorRenderingTests(GeneratorManifestTests):
    def build_fixture(self, source_name: str, output_name: str):
        generator = self.load_generator()
        self.assertTrue(
            hasattr(generator, "build_page"),
            "generator must expose build_page",
        )
        temporary_directory = tempfile.TemporaryDirectory()
        output_path = Path(temporary_directory.name) / output_name
        generator.build_page(
            ROOT / source_name,
            output_path,
            HERE / "m0-learning-guide.html",
        )
        html = output_path.read_text(encoding="utf-8")
        return temporary_directory, output_path, BeautifulSoup(html, "html.parser")

    def test_generated_page_recovers_source_bytes_and_digest(self):
        temporary_directory, _, soup = self.build_fixture(
            "M1.md",
            "m1-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)

        source_bytes = (ROOT / "M1.md").read_bytes()
        embedded = base64.b64decode(
            soup.select_one("#source-markdown").get_text(strip=True)
        )
        self.assertEqual(source_bytes, embedded)
        self.assertEqual(
            hashlib.sha256(source_bytes).hexdigest(),
            soup.select_one('meta[name="source-sha256"]')["content"],
        )

    def test_generated_page_reuses_m0_style_verbatim(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "M1.md",
            "m1-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)
        reference_soup = BeautifulSoup(
            (HERE / "m0-learning-guide.html").read_text(encoding="utf-8"),
            "html.parser",
        )

        reference_style = reference_soup.select_one("style").string
        generated_style = generated_soup.select_one("style").string
        self.assertIsNotNone(reference_style)
        self.assertIsNotNone(generated_style)
        self.assertTrue(generated_style.startswith(reference_style))

    def test_generated_page_has_complete_sections_toc_and_heading_text(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "M1.md",
            "m1-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)
        source = (ROOT / "M1.md").read_text(encoding="utf-8")
        markdown_h1 = markdown_headings(source, 1)
        markdown_h2 = markdown_headings(source, 2)
        markdown_h3 = markdown_headings(source, 3)

        self.assertEqual(markdown_h1, [generated_soup.select_one("h1").get_text()])
        self.assertEqual(len(markdown_h2), len(generated_soup.select("section.glass-card")))
        self.assertEqual(len(markdown_h2), len(generated_soup.select("#toc-list a")))
        self.assertEqual(markdown_h2, [node.get_text() for node in generated_soup.select("h2")])
        self.assertEqual(markdown_h3, [node.get_text() for node in generated_soup.select("h3")])
        ids = [node["id"] for node in generated_soup.select("[id]")]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(
            all(
                generated_soup.select_one(link["href"])
                for link in generated_soup.select("#toc-list a")
            )
        )

    def test_generated_long_plan_preserves_tables_and_wraps_them(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "00-execution-plan.md",
            "00-execution-plan.html",
        )
        self.addCleanup(temporary_directory.cleanup)

        self.assertGreaterEqual(len(generated_soup.select("table")), 4)
        self.assertTrue(
            all(
                table.parent.get("class") == ["table-wrap"]
                for table in generated_soup.select("table")
            )
        )

    def test_checklist_storage_keys_are_namespaced_per_page(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "M1.md",
            "m1-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)

        checkboxes = generated_soup.select(".completion-checkbox")
        self.assertGreater(len(checkboxes), 0)
        self.assertTrue(
            all(
                checkbox["data-check-key"].startswith("m1-learning-guide-check-")
                for checkbox in checkboxes
            )
        )

    def test_m0_code_steps_metadata_closing_note_and_links_are_preserved(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "M1.md",
            "m1-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)

        self.assertEqual(2, len(generated_soup.select(".code-shell")))
        self.assertEqual(2, len(generated_soup.select(".copy-code")))
        self.assertEqual(2, len(generated_soup.select("ol.steps")))
        self.assertEqual(2, len(generated_soup.select(".meta-strip")))
        self.assertEqual(1, len(generated_soup.select(".closing-note")))
        self.assertGreater(len(generated_soup.select('a[href^="https://"]')), 0)

    def test_inline_markdown_in_document_title_renders_as_plain_text(self):
        temporary_directory, _, generated_soup = self.build_fixture(
            "M2-工具层契约.md",
            "m2-learning-guide.html",
        )
        self.addCleanup(temporary_directory.cleanup)
        expected_title = (
            "模块 2 · 工具层契约（Java 网关 5 只读工具 + raw 通道）"
            "— 版本 C · 从 0 新建"
        )

        self.assertEqual(expected_title, generated_soup.select_one("h1").get_text())
        self.assertEqual(expected_title, generated_soup.title.get_text())


if __name__ == "__main__":
    unittest.main()
