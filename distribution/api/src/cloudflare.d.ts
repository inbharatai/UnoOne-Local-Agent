interface R2Range {
  offset?: number
  length?: number
  suffix?: number
}

interface R2GetOptions {
  range?: Headers | R2Range
}

interface R2Object {
  readonly key: string
  readonly size: number
  readonly etag: string
  readonly httpEtag: string
  readonly range?: R2Range
  writeHttpMetadata(headers: Headers): void
}

interface R2ObjectBody extends R2Object {
  readonly body: ReadableStream<Uint8Array>
}

interface R2Bucket {
  head(key: string): Promise<R2Object | null>
  get(key: string, options?: R2GetOptions): Promise<R2ObjectBody | null>
}
