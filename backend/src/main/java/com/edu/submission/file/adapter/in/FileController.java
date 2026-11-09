package com.edu.submission.file.adapter.in;

import com.edu.submission.file.adapter.in.dto.FileObjectView;
import com.edu.submission.file.application.FileUploadService;
import com.edu.submission.shared.security.CurrentUser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class FileController {

    private final FileUploadService fileUploadService;

    public FileController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/assignments/{assignmentId}/files")
    public ResponseEntity<FileObjectView> upload(@PathVariable UUID assignmentId,
                                                 @RequestParam("file") MultipartFile file) {
        FileObjectView view = fileUploadService.upload(CurrentUser.require(), assignmentId, file);
        return ResponseEntity.created(URI.create("/api/v1/files/" + view.id())).body(view);
    }

    @GetMapping("/files/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        FileUploadService.DownloadedFile file = fileUploadService.download(CurrentUser.require(), id);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.mimeType());
        } catch (Exception exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.content().length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new ByteArrayResource(file.content()));
    }
}
