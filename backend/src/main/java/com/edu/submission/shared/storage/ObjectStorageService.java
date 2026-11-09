package com.edu.submission.shared.storage;

import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.config.StorageProperties;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class ObjectStorageService {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public ObjectStorageService(S3Client s3Client, S3Presigner presigner,
                                StorageProperties properties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    public void put(String objectKey, byte[] content, String contentType) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE",
                    "文件存储暂不可用", "请稍后重试或联系系统管理员。");
        }
    }

    public byte[] get(String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
            return response.asByteArray();
        } catch (S3Exception exception) {
            throw ApiException.notFound("OBJECT_NOT_FOUND", "文件不存在", "对象可能已被删除或尚未生成。");
        }
    }

    public String presignedGetUrl(String objectKey, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(objectKey)
                        .build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }
}
