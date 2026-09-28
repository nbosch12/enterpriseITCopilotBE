package com.bosch.demo.docgrounding.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class S3ObjectStoreService {
    private final S3Client s3Client;
    private final AppProperties properties;

    public S3ObjectStoreService(S3Client s3Client, AppProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    public void uploadText(String key, String content, Map<String, String> metadata) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .contentType("text/plain; charset=utf-8")
                .metadata(metadata)
                .build();
        s3Client.putObject(request, RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    public List<String> listKeys(String prefix, int maxKeys) {
        List<String> keys = new ArrayList<>();
        String continuationToken = null;

        while (keys.size() < maxKeys) {
            int requestLimit = Math.min(1000, maxKeys - keys.size());
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(properties.getS3().getBucket())
                    .prefix(prefix)
                    .maxKeys(requestLimit)
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);

            for (S3Object object : response.contents()) {
                keys.add(object.key());
            }

            if (!Boolean.TRUE.equals(response.isTruncated()) || response.nextContinuationToken() == null) {
                break;
            }

            continuationToken = response.nextContinuationToken();
        }

        return keys;
    }

    public String readText(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(key)
                .build();

        return s3Client.getObjectAsBytes(request).asString(StandardCharsets.UTF_8);
    }

    public String readTextIfExists(String key) {
        try {
            return readText(key);
        } catch (NoSuchKeyException ex) {
            return null;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return null;
            }
            throw ex;
        }
    }

    public int deletePrefix(String prefix) {
        String continuationToken = null;
        int deleted = 0;

        while (true) {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(properties.getS3().getBucket())
                    .prefix(prefix)
                    .maxKeys(1000)
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);
            for (S3Object object : response.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(properties.getS3().getBucket())
                        .key(object.key())
                        .build());
                deleted++;
            }

            if (!Boolean.TRUE.equals(response.isTruncated()) || response.nextContinuationToken() == null) {
                break;
            }

            continuationToken = response.nextContinuationToken();
        }

        return deleted;
    }
}
