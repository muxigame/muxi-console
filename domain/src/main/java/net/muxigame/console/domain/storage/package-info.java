/**
 * Optional SDK-free object storage. Server configuration is supplied by an explicit external private
 * path, never bundled in this JAR. A missing file disables the adapter; malformed configuration fails
 * closed. Keys use the external keyPrefix followed by hash[0..2]/hash.rom.
 *
 * <p>S3 uploads bind its native SHA-256 checksum. OSS URL V4 uses UNSIGNED-PAYLOAD: SHA metadata and HEAD
 * do not prove a client's body SHA-256. The migration-only local PUT computes SHA and Content-MD5 in
 * one local pass and lets OSS check that body. Neither provider is verified by downloading ROM bodies.
 * OSS forbids overwrite only on a bucket that has never enabled versioning; operator must independently
 * check that precondition and deny versioning changes to the service credential. No bucket probe or
 * management operation is performed by this package. Single PUTs are bounded to 5,000,000,000 bytes.
 *
 * <p>Primary specifications:
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html">AWS S3 query SigV4</a>,
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">S3 checksums and conditional PUT</a>,
 * <a href="https://www.alibabacloud.com/help/en/oss/developer-reference/add-signatures-to-urls">OSS URL V4</a>,
 * <a href="https://www.alibabacloud.com/help/en/oss/developer-reference/putobject">OSS PUT constraints</a>.
 * All code in this package is independently written MIT code, with no cloud SDK dependency.
 */
package net.muxigame.console.domain.storage;
