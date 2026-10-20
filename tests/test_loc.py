from legacy_audit.inventory.loc import count_loc


def loc(text: str, lang: str) -> tuple[int, int, int]:
    r = count_loc(text, lang)
    return r.code, r.comment, r.blank


def test_java_line_block_and_mixed():
    src = """/**
 * Doc
 */
public class A { // trailing comment is code

    int x; /* inline */
    /* one-line block */
}
"""
    assert loc(src, "java") == (3, 4, 1)


def test_block_comment_with_blank_line_inside():
    assert loc("/*\n\n*/\nint a;\n", "c") == (1, 2, 1)


def test_code_after_block_end_on_same_line():
    assert loc("/* a\n b */ int x;\n", "c") == (1, 1, 0)


def test_sql():
    assert loc("-- c\nSELECT 1 FROM DUAL; -- trailing\n/* x */\n\n", "sql") == (1, 2, 1)


def test_shell_and_properties():
    assert loc("#!/bin/sh\n# c\necho a # b\n\n", "shell") == (1, 2, 1)
    assert loc("# c\n! c\na=b\n", "properties") == (1, 2, 0)


def test_xml_and_jsp():
    assert loc("<!-- a\n b -->\n<x/>\n", "xml") == (1, 2, 0)
    assert loc("<%-- a --%>\n<p>x</p><!-- c -->\n", "jsp") == (1, 1, 0)


def test_cobol_fixed_format():
    src = (
        "000100 IDENTIFICATION DIVISION.\n"
        "000200* comment line\n"
        "000300/ page eject comment\n"
        "000400\n"
        "000500 PROGRAM-ID. PAYROLL.                                             PAYR0010\n"
        "      *> free style comment\n"
    )
    assert loc(src, "cobol") == (2, 3, 1)


def test_cobol_free_format():
    src = ">>SOURCE FORMAT FREE\nIDENTIFICATION DIVISION.\n*> comment\n\n"
    assert loc(src, "cobol") == (2, 1, 1)


def test_jcl():
    assert loc("//JOB1 JOB\n//* comment\n//STEP1 EXEC PGM=X\n\n", "jcl") == (2, 1, 1)


def test_batch_and_mqsc():
    assert loc("@echo off\nREM c\n:: c\nrem\n", "batch") == (1, 3, 0)
    assert loc("* comment\nDEFINE QLOCAL(A)\n", "mqsc") == (1, 1, 0)


def test_jil():
    assert loc("/* box */\ninsert_job: A\n# c\n", "jil") == (1, 2, 0)


def test_docs_are_comment():
    assert loc("Title\n\ntext\n", "text") == (0, 2, 1)


def test_unrecognised_language_counts_nonblank_as_code():
    assert loc("a\n\nb\n", "other (.xyz)") == (2, 0, 1)


def test_data_and_backup_copies_are_not_measured():
    assert loc("1;2\n3;4\n", "data") == (0, 0, 0)
    assert loc("class A {}\n", "backup") == (0, 0, 0)


def test_more_script_languages():
    assert loc("# fix\n{ print $1 }\n", "awk") == (1, 1, 0)
    assert loc("' note\nWScript.Echo 1\n", "vbscript") == (1, 1, 0)
    assert loc("-- load\nLOAD DATA\n", "sqlloader") == (1, 1, 0)


def test_windows_line_endings():
    assert loc("int a;\r\n// c\r\n\r\n", "java") == (1, 1, 1)
