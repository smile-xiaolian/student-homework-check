package com.edu.submission.library.adapter.in;

import com.edu.submission.library.adapter.in.dto.LibraryCreateRequest;
import com.edu.submission.library.adapter.in.dto.LibraryEntryView;
import com.edu.submission.library.adapter.in.dto.LibraryView;
import com.edu.submission.library.application.LibraryService;
import com.edu.submission.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/courses/{courseId}/libraries")
    public List<LibraryView> list(@PathVariable UUID courseId) {
        return libraryService.list(CurrentUser.require(), courseId);
    }

    @PostMapping("/courses/{courseId}/libraries")
    public ResponseEntity<LibraryView> create(@PathVariable UUID courseId,
                                              @Valid @RequestBody LibraryCreateRequest request) {
        LibraryView view = libraryService.create(CurrentUser.require(), courseId, request);
        return ResponseEntity.created(URI.create("/api/v1/libraries/" + view.id())).body(view);
    }

    @DeleteMapping("/libraries/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        libraryService.delete(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/libraries/{id}/entries")
    public ResponseEntity<LibraryEntryView> addEntry(@PathVariable UUID id,
                                                     @RequestParam("file") MultipartFile file) {
        LibraryEntryView view = libraryService.addEntry(CurrentUser.require(), id, file);
        return ResponseEntity.created(URI.create("/api/v1/libraries/" + id + "/entries/" + view.id())).body(view);
    }

    @DeleteMapping("/libraries/{id}/entries/{entryId}")
    public ResponseEntity<Void> removeEntry(@PathVariable UUID id, @PathVariable UUID entryId) {
        libraryService.removeEntry(CurrentUser.require(), id, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/libraries/{id}/entries")
    public List<LibraryEntryView> entries(@PathVariable UUID id) {
        return libraryService.entries(CurrentUser.require(), id);
    }
}
