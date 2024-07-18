package stirling.software.SPDF.controller.api;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.itextpdf.text.Document;
import com.itextpdf.text.pdf.PdfCopy;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSmartCopy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.SneakyThrows;
import stirling.software.SPDF.model.api.general.MergePdfsRequest;
import stirling.software.SPDF.utils.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/general")
@Tag(name = "General", description = "General APIs")
public class MergeController {

    private static final Logger logger = LoggerFactory.getLogger(MergeController.class);

    // Merges a list of PDDocument objects into a single PDDocument
    public PDDocument mergeDocuments(List<PDDocument> documents) throws IOException {
        PDDocument mergedDoc = new PDDocument();
        for (PDDocument doc : documents) {
            for (PDPage page : doc.getPages()) {
                mergedDoc.addPage(page);
            }
        }
        return mergedDoc;
    }

    // Returns a comparator for sorting MultipartFile arrays based on the given sort type
    private Comparator<MultipartFile> getSortComparator(String sortType) {
        switch (sortType) {
            case "byFileName":
                return Comparator.comparing(MultipartFile::getOriginalFilename);
            case "byDateModified":
                return (file1, file2) -> {
                    try {
                        BasicFileAttributes attr1 =
                                Files.readAttributes(
                                        Paths.get(file1.getOriginalFilename()),
                                        BasicFileAttributes.class);
                        BasicFileAttributes attr2 =
                                Files.readAttributes(
                                        Paths.get(file2.getOriginalFilename()),
                                        BasicFileAttributes.class);
                        return attr1.lastModifiedTime().compareTo(attr2.lastModifiedTime());
                    } catch (IOException e) {
                        return 0; // If there's an error, treat them as equal
                    }
                };
            case "byDateCreated":
                return (file1, file2) -> {
                    try {
                        BasicFileAttributes attr1 =
                                Files.readAttributes(
                                        Paths.get(file1.getOriginalFilename()),
                                        BasicFileAttributes.class);
                        BasicFileAttributes attr2 =
                                Files.readAttributes(
                                        Paths.get(file2.getOriginalFilename()),
                                        BasicFileAttributes.class);
                        return attr1.creationTime().compareTo(attr2.creationTime());
                    } catch (IOException e) {
                        return 0; // If there's an error, treat them as equal
                    }
                };
            case "byPDFTitle":
                return (file1, file2) -> {
                    try (PDDocument doc1 = Loader.loadPDF(file1.getBytes());
                            PDDocument doc2 = Loader.loadPDF(file2.getBytes())) {
                        String title1 = doc1.getDocumentInformation().getTitle();
                        String title2 = doc2.getDocumentInformation().getTitle();
                        return title1.compareTo(title2);
                    } catch (IOException e) {
                        return 0;
                    }
                };
            case "orderProvided":
            default:
                return (file1, file2) -> 0; // Default is the order provided
        }
    }

    @PostMapping(consumes = "multipart/form-data", value = "/merge-pdfs")
    @Operation(
            summary = "Merge multiple PDF files into one",
            description =
                    "This endpoint merges multiple PDF files into a single PDF file. The merged file will contain all pages from the input files in the order they were provided. Input:PDF Output:PDF Type:MISO")
    public ResponseEntity<byte[]> mergePdfs(@ModelAttribute MergePdfsRequest form)
            throws IOException {
        List<File> filesToDelete = new ArrayList<>(); // List of temporary files to delete
        PDDocument mergedDocument = null;

        boolean removeCertSign = form.isRemoveCertSign();

        try {
            MultipartFile[] files = form.getFileInput();
            Arrays.sort(
                    files,
                    getSortComparator(form.getSortType())); // Sort files based on the given sort type

            List<BufferedInputStream> inputStreams =
                    Stream.of(files)
                            .map(
                                    t -> {
                                        try {
                                            return new BufferedInputStream(t.getInputStream());
                                        } catch (IOException e) {
                                            throw new IllegalStateException(e);
                                        }
                                    })
                            .toList();
            byte[] mergedPdfBytes = concat(inputStreams);
            inputStreams.forEach(
                    i -> {
                        try {
                            i.close();
                        } catch (IOException e) {
                        }
                    });

            // Remove signatures if removeCertSign is true
            if (removeCertSign) {
                // Load the merged PDF document
                mergedDocument = Loader.loadPDF(mergedPdfBytes);

                PDDocumentCatalog catalog = mergedDocument.getDocumentCatalog();
                PDAcroForm acroForm = catalog.getAcroForm();
                if (acroForm != null) {
                    List<PDField> fieldsToRemove =
                            acroForm.getFields().stream()
                                    .filter(field -> field instanceof PDSignatureField)
                                    .collect(Collectors.toList());

                    if (!fieldsToRemove.isEmpty()) {
                        acroForm.flatten(
                                fieldsToRemove,
                                false); // Flatten the fields, effectively removing them
                    }
                }
                // Save the modified document to a new ByteArrayOutputStream
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 1024);
                mergedDocument.save(baos);
                mergedPdfBytes = baos.toByteArray();
            }

            String mergedFileName =
                    files[0].getOriginalFilename().replaceFirst("[.][^.]+$", "")
                            + "_merged_unsigned.pdf";
            return WebResponseUtils.bytesToWebResponse(
                    mergedPdfBytes, mergedFileName); // Return the modified PDF

        } catch (Exception ex) {
            logger.error("Error in merge pdf process", ex);
            throw ex;
        } finally {
            for (File file : filesToDelete) {
                if (file != null) {
                    Files.deleteIfExists(file.toPath()); // Delete temporary files
                }
            }
            if (mergedDocument != null) {
                mergedDocument.close(); // Close the merged document
            }
        }
    }

    @SneakyThrows
    public byte[] concat(List<BufferedInputStream> docs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
        Document document = new Document();
        try {
            PdfCopy copy = new PdfSmartCopy(document, out);
            document.open();
            for (InputStream doc : docs) {
                PdfReader reader = new PdfReader(doc);
                copy.addDocument(reader);
                reader.close();
            }
        } finally {
            document.close();
        }
        return out.toByteArray();
    }
}
