from backend.app.main import collect_ocr_spans


def test_ocr_blocks_keep_text_and_image_regions_without_invented_confidence():
    spans = collect_ocr_spans([{"pages": [{"page_num": 1, "blocks": [
        {"block_id": "p1-b1", "text": "ആലപ്പുഴ", "bbox_norm": [0.1, 0.2, 0.4, 0.3], "reading_order": 1}
    ]}]}])
    assert spans[0]["text"] == "ആലപ്പുഴ"
    assert spans[0]["bbox_norm"] == [0.1, 0.2, 0.4, 0.3]
    assert spans[0]["confidence"] is None

