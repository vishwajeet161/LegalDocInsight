import io
import json
import sys
import time
import urllib.error
import urllib.request
import zipfile

base = "http://127.0.0.1:" + sys.argv[1] + "/api/documents"

def pdf():
    stream = b"BT /F1 18 Tf 72 700 Td (Synthetic agreement) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode() + obj + b"\nendobj\n")
    start = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode())
    for offset in offsets:
        out.extend(f"{offset:010d} 00000 n \n".encode())
    out.extend(f"trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode())
    return bytes(out)

def docx():
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Synthetic agreement</w:t></w:r></w:p></w:body></w:document>')
    return out.getvalue()

def request(path, token=None, data=None, content_type=None, extra_headers=None):
    headers = {}
    if token: headers["X-Document-Token"] = token
    if content_type: headers["Content-Type"] = content_type
    if extra_headers: headers.update(extra_headers)
    req = urllib.request.Request(base + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, json.load(response), response.headers
    except urllib.error.HTTPError as error:
        return error.code, None, error.headers

def upload(name, mime, body):
    boundary = "synthetic-boundary"
    form = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n'.encode() + body + f"\r\n--{boundary}--\r\n".encode())
    status, response, _ = request("/upload", data=form, content_type="multipart/form-data; boundary=" + boundary)
    assert status == 202, f"upload {name}: HTTP {status}"
    return response["documentId"], response["accessToken"]

def wait_for_extracted(doc_id, token):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            status, body, headers = request("/" + doc_id, token)
            if status == 200:
                assert "no-store" in headers.get("Cache-Control", "")
                assert not body.get("extractedText")
                if body["status"] == "EXTRACTED":
                    return body
                assert body["status"] != "FAILED", "extraction failed with code " + str(body.get("errorCode"))
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise AssertionError("timed out waiting for extraction")

items = [("synthetic.pdf", "application/pdf", pdf()), ("synthetic.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx())]
uploaded = []
for name, mime, data in items:
    doc_id, token = upload(name, mime, data)
    assert request("/" + doc_id)[0] == 403
    assert request("/" + doc_id, "invalid")[0] == 403
    assert request("/" + doc_id, extra_headers={"X-User-Id": "synthetic-user"})[0] == 403
    body = wait_for_extracted(doc_id, token)
    assert body["characterCount"] == 19
    if name.endswith(".pdf"): assert body["pageCount"] == 1
    status, text, _ = request("/" + doc_id + "/text", token)
    assert status == 200 and text["extractedText"] == "Synthetic agreement"
    uploaded.append((doc_id, token))
    print(name + ": authorized extraction and polling passed", flush=True)

def wait_for_failure(doc_id, token, expected):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        status, body, _ = request("/" + doc_id, token)
        assert status == 200
        if body["status"] == "FAILED":
            assert body["errorCode"] == expected, (expected, body.get("errorCode"))
            print("safe failure: " + expected, flush=True)
            return
        assert body["status"] != "EXTRACTED"
        time.sleep(1)
    raise AssertionError("timed out waiting for safe failure")

blank = pdf().replace(b"Synthetic agreement", b"                   ")
doc_id, token = upload("blank.pdf", "application/pdf", blank)
wait_for_failure(doc_id, token, "EMPTY_TEXT")

doc_id, token = upload("malformed.docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b"PK\x03\x04not-a-docx")
wait_for_failure(doc_id, token, "MALFORMED_DOCUMENT")

# The harmless standard antivirus test file is stored inside a synthetic DOCX ZIP.
eicar = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + b"EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
infected = io.BytesIO(docx())
with zipfile.ZipFile(infected, "a", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("word/embeddings/eicar.com", eicar)
doc_id, token = upload("synthetic.docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", infected.getvalue())
wait_for_failure(doc_id, token, "MALWARE_DETECTED")
