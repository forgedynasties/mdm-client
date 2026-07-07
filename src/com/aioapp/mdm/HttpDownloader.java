package com.aioapp.mdm;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Resumable, retrying HTTP downloader shared by the APK-install and OTA paths.
 *
 * Both paths previously did a single-shot {@link HttpURLConnection} GET with no
 * retry and no completeness check, so a stale keep-alive socket (surfacing as
 * "unexpected end of stream") or a transient stall on a large file (surfacing as
 * "SocketTimeoutException") killed the whole download — and a truncated response
 * that ended "cleanly" was silently accepted, then failed later as a corrupt APK.
 *
 * This helper retries with HTTP Range resume: each attempt continues from the
 * bytes already on disk, verifies the final size, and only gives up after all
 * attempts are exhausted. It sets {@code Connection: close} so a large transfer
 * never reuses (or leaves behind) a pooled socket that S3 may have closed.
 */
public final class HttpDownloader {
    private static final String TAG = "HttpDownloader";

    /** Reports bytes-so-far and total (total is -1 when unknown). */
    public interface ProgressCallback {
        void onProgress(long downloaded, long total);
    }

    /** Returns true to abort the download (e.g. a newer command superseded it). */
    public interface CancelCheck {
        boolean isCancelled();
    }

    private HttpDownloader() {}

    /**
     * Downloads {@code url} to {@code dest}, resuming from any bytes already present.
     *
     * @param expectedSize authoritative total size in bytes, or &lt;=0 if unknown
     *                     (falls back to the response Content-Length).
     * @param etag         object ETag for If-Range (may be null); guards against the
     *                     object changing mid-resume — a mismatch makes the origin
     *                     return the full body and we restart from byte 0.
     * @param maxAttempts  total attempts including the first.
     * @throws IOException if every attempt fails, or the download is cancelled.
     */
    public static void downloadWithResume(String url, File dest, long expectedSize, String etag,
                                          int connectTimeoutMs, int readTimeoutMs, int maxAttempts,
                                          ProgressCallback progress, CancelCheck cancelled)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelled != null && cancelled.isCancelled()) throw new IOException("download cancelled");
            if (attempt > 1) {
                long backoffMs = Math.min(1000L << (attempt - 2), 16_000L); // 1s,2s,4s,8s,16s cap
                Log.w(TAG, "retry " + attempt + "/" + maxAttempts + " after " + backoffMs + "ms: "
                        + (last != null ? last.getMessage() : ""));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during backoff");
                }
            }
            try {
                attemptDownload(url, dest, expectedSize, etag, connectTimeoutMs, readTimeoutMs,
                        progress, cancelled);
                return; // success
            } catch (IOException e) {
                last = e;
                // A cancel must abort immediately, not burn the remaining retries.
                if (cancelled != null && cancelled.isCancelled()) throw e;
                // Otherwise keep the partial file so the next attempt resumes from it.
            }
        }
        throw last != null ? last : new IOException("download failed");
    }

    private static void attemptDownload(String url, File dest, long expectedSize, String etag,
                                        int connectTimeoutMs, int readTimeoutMs,
                                        ProgressCallback progress, CancelCheck cancelled)
            throws IOException {
        long existing = dest.exists() ? dest.length() : 0;
        if (expectedSize > 0) {
            if (existing == expectedSize) {                 // already complete from a prior attempt
                if (progress != null) progress.onProgress(existing, expectedSize);
                return;
            }
            if (existing > expectedSize) {                  // partial file is bogus — start over
                deleteQuietly(dest);
                existing = 0;
            }
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        // Don't reuse a pooled keep-alive socket for a large transfer — a stale one
        // the origin already closed surfaces as "unexpected end of stream".
        conn.setRequestProperty("Connection", "close");
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=" + existing + "-");
            if (etag != null && !etag.isEmpty()) conn.setRequestProperty("If-Range", etag);
        }

        try {
            conn.connect();
            int code = conn.getResponseCode();
            boolean resuming;
            if (code == HttpURLConnection.HTTP_PARTIAL) {            // 206 — resume accepted
                resuming = true;
            } else if (code == HttpURLConnection.HTTP_OK) {          // 200 — server sent the full body
                resuming = false;
                if (existing > 0) { deleteQuietly(dest); existing = 0; }
            } else if (code == 416) {                               // Range Not Satisfiable
                // Almost always means we already have the whole file. Accept if non-empty.
                if (dest.length() > 0) {
                    if (progress != null) progress.onProgress(dest.length(), dest.length());
                    return;
                }
                throw new IOException("HTTP 416");
            } else {
                throw new IOException("HTTP " + code);
            }

            long contentLength = conn.getContentLengthLong(); // bytes in THIS response body
            long total = expectedSize > 0
                    ? expectedSize
                    : (contentLength >= 0 ? contentLength + (resuming ? existing : 0) : -1);

            long downloaded = resuming ? existing : 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(dest, resuming)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.isCancelled()) throw new IOException("download cancelled");
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (progress != null) progress.onProgress(downloaded, total);
                }
            }

            // Completeness check: a truncated stream that ends without an exception must
            // still fail here so the next attempt resumes instead of installing a partial file.
            if (total > 0 && dest.length() != total) {
                throw new IOException("incomplete download: " + dest.length() + "/" + total + " bytes");
            }
            if (dest.length() == 0) throw new IOException("empty download");
        } finally {
            conn.disconnect();
        }
    }

    private static void deleteQuietly(File f) {
        try { if (f.exists()) f.delete(); } catch (Exception ignored) {}
    }
}
