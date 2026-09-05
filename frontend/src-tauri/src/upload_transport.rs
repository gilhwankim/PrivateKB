use serde::Deserialize;
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpStream};
use std::time::{Duration, Instant};

pub(crate) const MAX_FILE_BYTES: u64 = 100_000_000;
pub(crate) const COPY_BUFFER_BYTES: usize = 64 * 1024;
const MAX_RESPONSE_BYTES: usize = 32 * 1024;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UploadLimits { max_file_bytes: u64 }

// 파일 선택·일괄 업로드 시작 때 각각 한 번만 조회한다. DB·Ollama 호출은 없다.
pub(crate) fn load_upload_limit(port: u16) -> Result<u64, String> {
    let unavailable = || "파일 크기 제한을 확인할 수 없습니다. 프로그램 상태를 확인하고 다시 시도해 주세요.".to_owned();
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    let mut stream = TcpStream::connect_timeout(&address.into(), Duration::from_secs(2)).map_err(|_| unavailable())?;
    stream.set_write_timeout(Some(Duration::from_secs(2))).map_err(|_| unavailable())?;
    let request = format!("GET /api/document-upload-policy HTTP/1.0\r\nHost: 127.0.0.1:{port}\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
    stream.write_all(request.as_bytes()).map_err(|_| unavailable())?;
    let response = read_response(&mut stream, Duration::from_secs(3)).map_err(|_| unavailable())?;
    parse_upload_limit(&response).map_err(|_| unavailable())
}

fn parse_upload_limit(response: &[u8]) -> Result<u64, ()> {
    if response.len() > MAX_RESPONSE_BYTES { return Err(()); }
    let split = response.windows(4).position(|w| w == b"\r\n\r\n").ok_or(())?;
    let header = std::str::from_utf8(&response[..split]).map_err(|_| ())?;
    let mut lines = header.split("\r\n");
    let mut status = lines.next().ok_or(())?.split_whitespace();
    if !matches!(status.next(), Some("HTTP/1.0" | "HTTP/1.1")) || status.next() != Some("200") { return Err(()); }
    let body = &response[split + 4..];
    let mut json = false;
    for line in lines {
        let (name, value) = line.split_once(':').ok_or(())?;
        let value = value.trim();
        if name.eq_ignore_ascii_case("transfer-encoding") || name.eq_ignore_ascii_case("content-encoding") { return Err(()); }
        if name.eq_ignore_ascii_case("content-type") { json = value.split(';').next().is_some_and(|v| v.trim().eq_ignore_ascii_case("application/json")); }
        if name.eq_ignore_ascii_case("content-length") && value.parse::<usize>().ok() != Some(body.len()) { return Err(()); }
    }
    if !json { return Err(()); }
    let limits: UploadLimits = serde_json::from_slice(body).map_err(|_| ())?;
    if limits.max_file_bytes == 0 || limits.max_file_bytes > MAX_FILE_BYTES { return Err(()); }
    Ok(limits.max_file_bytes)
}

pub(crate) fn read_response(stream: &mut TcpStream, timeout: Duration) -> io::Result<Vec<u8>> {
    let deadline = Instant::now() + timeout;
    let mut response = Vec::with_capacity(2048);
    let mut buffer = [0_u8; 4096];
    loop {
        let remaining = deadline.checked_duration_since(Instant::now()).ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "응답 시간 초과"))?;
        stream.set_read_timeout(Some(remaining))?;
        let size = stream.read(&mut buffer)?;
        if size == 0 { return Ok(response); }
        if response.len() + size > MAX_RESPONSE_BYTES { return Err(io::Error::new(io::ErrorKind::InvalidData, "응답 크기 초과")); }
        response.extend_from_slice(&buffer[..size]);
    }
}

// 입력 크기에 비례하는 Vec을 만들지 않는다. 짧거나 늘어난 입력은 끝 경계 전 거부한다.
pub(crate) fn copy_exact_document(input: &mut impl Read, output: &mut impl Write, expected: u64) -> io::Result<()> {
    if expected == 0 || expected > MAX_FILE_BYTES { return Err(io::Error::new(io::ErrorKind::InvalidInput, "파일 크기 초과")); }
    let mut buffer = [0_u8; COPY_BUFFER_BYTES];
    let mut remaining = expected;
    while remaining > 0 {
        let wanted = remaining.min(COPY_BUFFER_BYTES as u64) as usize;
        let count = input.read(&mut buffer[..wanted])?;
        if count == 0 { return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "파일 크기 변경")); }
        output.write_all(&buffer[..count])?;
        remaining -= count as u64;
    }
    if input.read(&mut buffer[..1])? != 0 { return Err(io::Error::new(io::ErrorKind::InvalidData, "파일 크기 변경")); }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[ignore = "격리된 실제 서비스의 정책 응답 연결 검사"]
    fn policy_smoke() {
        let port = std::env::var("PRIVATEKB_REVEAL_SMOKE_PORT").unwrap().parse().unwrap();
        assert_eq!(load_upload_limit(port).unwrap(), 25_000_000);
    }

    #[test]
    fn accepts_decimal_profile_limits_and_rejects_invalid_policy_responses() {
        for bytes in [25_000_000, 50_000_000, 100_000_000] {
            let response = format!("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{{\"maxFileBytes\":{bytes}}}");
            assert_eq!(parse_upload_limit(response.as_bytes()), Ok(bytes));
        }
        for response in [
            "HTTP/1.0 404 Not Found\r\nContent-Type: application/json\r\n\r\n{}",
            "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{\"maxFileBytes\":0}",
            "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n{\"maxFileBytes\":100000001}",
            "HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n{}",
        ] { assert!(parse_upload_limit(response.as_bytes()).is_err()); }
    }

    #[test]
    fn streams_one_hundred_mb_with_at_most_sixty_four_kb_reads() {
        struct BoundedReader { remaining: u64, largest_read: usize }
        impl Read for BoundedReader {
            fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
                self.largest_read = self.largest_read.max(buffer.len());
                let size = self.remaining.min(buffer.len() as u64) as usize;
                buffer[..size].fill(42);
                self.remaining -= size as u64;
                Ok(size)
            }
        }
        let mut input = BoundedReader { remaining: MAX_FILE_BYTES, largest_read: 0 };
        copy_exact_document(&mut input, &mut io::sink(), MAX_FILE_BYTES).unwrap();
        assert_eq!(input.remaining, 0);
        assert_eq!(input.largest_read, COPY_BUFFER_BYTES);
    }

    #[test]
    fn streamed_bytes_match_and_changed_lengths_fail() {
        let mut output = Vec::new();
        copy_exact_document(&mut &b"abc"[..], &mut output, 3).unwrap();
        assert_eq!(output, b"abc");
        assert!(copy_exact_document(&mut &b"ab"[..], &mut io::sink(), 3).is_err());
        assert!(copy_exact_document(&mut &b"abcd"[..], &mut io::sink(), 3).is_err());
        assert!(copy_exact_document(&mut &b""[..], &mut io::sink(), MAX_FILE_BYTES + 1).is_err());
    }
}
