"""Text-to-HID units; the phone's own IME commits Chinese characters."""


def typing_units(text):
    if not text or len(text) > 160:
        raise ValueError('文本长度必须为1..160字符')
    if any(not (' ' <= char <= '~' or '\u4e00' <= char <= '\u9fff') for char in text):
        raise ValueError('首版支持基本汉字和单行ASCII；暂不支持emoji、中文标点或换行')
    from pypinyin import lazy_pinyin
    spellings = lazy_pinyin(text, errors=lambda value: list(value))
    if len(spellings) != len(text):
        raise ValueError('无法逐字转换拼音')
    units = []
    for char, spelling in zip(text, spellings):
        chinese = '\u4e00' <= char <= '\u9fff'
        if chinese and (not spelling.isascii() or not spelling.isalpha()):
            raise ValueError('无法转换汉字拼音：'+char)
        if not chinese and units and units[-1][1] is None:
            units[-1] = (units[-1][0]+char, None)
        else:
            units.append((char, spelling if chinese else None))
    return units
