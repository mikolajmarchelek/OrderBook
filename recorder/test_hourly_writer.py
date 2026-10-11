import gzip

from kraken_recorder import HourlyWriter

# 2026-10-11 18:59:59 UTC and one second later (19:00:00 UTC), in nanoseconds
T_1859 = 1_791_745_199 * 1_000_000_000
T_1900 = T_1859 + 1_000_000_000


def read_lines(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return f.read().splitlines()


def test_hour_key_is_utc():
    assert HourlyWriter.hour_key(T_1859) == "2026-10-11T18"
    assert HourlyWriter.hour_key(T_1900) == "2026-10-11T19"


def test_lines_go_to_the_file_of_their_hour(tmp_path):
    with HourlyWriter(tmp_path, "test") as w:
        w.write(T_1859, '{"a":1}')
        w.write(T_1900, '{"b":2}')

    assert read_lines(tmp_path / "test_2026-10-11T18.tsv.gz") == [f'{T_1859}\t{{"a":1}}']
    assert read_lines(tmp_path / "test_2026-10-11T19.tsv.gz") == [f'{T_1900}\t{{"b":2}}']


def test_restarting_in_the_same_hour_appends(tmp_path):
    with HourlyWriter(tmp_path, "test") as w:
        w.write(T_1859, "first")
    with HourlyWriter(tmp_path, "test") as w:       # recorder restarted
        w.write(T_1859 + 1, "second")

    assert read_lines(tmp_path / "test_2026-10-11T18.tsv.gz") == [f"{T_1859}\tfirst", f"{T_1859 + 1}\tsecond"]


def test_close_is_safe_to_call_twice(tmp_path):
    w = HourlyWriter(tmp_path, "test")
    w.write(T_1859, "x")
    w.close()
    w.close()