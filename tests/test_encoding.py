from legacy_audit.inventory.encoding import detect_encoding, is_binary


def test_utf8_and_bom():
    assert detect_encoding("héllo wörld".encode()) == "utf-8"
    assert detect_encoding(b"\xef\xbb\xbfhello") == "utf-8-sig"
    assert detect_encoding(b"plain ascii") == "utf-8"


def test_cp1252():
    text = "Société générale: prix réduit à 10 € pour les clients fidèles. " * 3
    assert detect_encoding(text.encode("cp1252")) == "cp1252"


def test_shift_jis():
    text = "注文管理システムのバッチ処理です。顧客情報を更新します。" * 4
    assert detect_encoding(text.encode("shift_jis")) == "shift_jis"


def test_ebcdic():
    cobol = (
        "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. PAYROLL.\n"
        "       PROCEDURE DIVISION.\n           DISPLAY 'HELLO'.\n           STOP RUN.\n"
    )
    assert detect_encoding(cobol.encode("cp037")) == "cp037"


def test_binary():
    assert is_binary(b"GIF89a\x01\x00\x00\x00")
    assert not is_binary(b"text only")
    assert not is_binary("hi".encode("utf-16"))
