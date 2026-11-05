package ru.tsu.accounts.web;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.report.ReportAuditService;
import ru.tsu.accounts.report.ReportExportService;
import ru.tsu.accounts.report.ReportQuery;
import ru.tsu.accounts.report.ReportService;
import ru.tsu.accounts.report.ReportType;

@Controller
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','MANAGER')")
public class ReportController {
    private final ReportService reports;
    private final ReportExportService export;
    private final ReportAuditService audit;

    public ReportController(ReportService reports, ReportExportService export, ReportAuditService audit) {
        this.reports = reports;
        this.export = export;
        this.audit = audit;
    }

    @GetMapping("/reports")
    public String parameters(@RequestParam MultiValueMap<String, String> parameters, Model model,
                             HttpServletResponse response) {
        return form(ReportWebParameters.singleValues(parameters), model, response, null);
    }

    @GetMapping({"/reports/result", "/reports/print"})
    public String result(@RequestParam MultiValueMap<String, String> parameters, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        ReportQuery query;
        try {
            query = query(parameters);
        } catch (UserCommandException ex) {
            if (ex.status() != HttpStatus.BAD_REQUEST || !"REPORT_VALIDATION".equals(ex.reasonCode())) {
                throw ex;
            }
            return form(parameters.toSingleValueMap(), model, response, ex.getMessage());
        }
        var report = reports.generate(query);
        boolean print = request.getRequestURI().endsWith("/print");
        audit.record(report, print ? "PRINT" : "HTML", RequestIdFilter.id(request));
        long pages = ReportWebParameters.totalPages(report.totalRows(), query.size());
        model.addAttribute("report", report);
        model.addAttribute("columns", query.type().getColumns());
        model.addAttribute("period", ReportWebParameters.filters(query));
        model.addAttribute("totalPages", pages);
        model.addAttribute("printView", print);
        model.addAttribute("editUrl", link(request, "/reports", query, null));
        model.addAttribute("printUrl", link(request, "/reports/print", query, query.page()));
        model.addAttribute("resultUrl", link(request, "/reports/result", query, query.page()));
        model.addAttribute("csvUrl", link(request, "/reports.csv", query, null));
        model.addAttribute("jsonUrl", link(request, "/api/v1/reports/" + query.type().name(), query, query.page()));
        if (query.page() > 0) {
            model.addAttribute("firstUrl", link(request, "/reports/result", query, 0));
            if (query.page() <= pages) {
                model.addAttribute("previousUrl", link(request, "/reports/result", query, query.page() - 1));
            }
        }
        if ((long) query.page() + 1 < pages) {
            model.addAttribute("nextUrl", link(request, "/reports/result", query, query.page() + 1));
        }
        noStore(response);
        return "reports/result";
    }

    @GetMapping("/reports.csv")
    public void csv(@RequestParam MultiValueMap<String, String> parameters, HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        try (var document = export.createCsv(query(parameters))) {
            audit.record(document.metadata(), "CSV", RequestIdFilter.id(request));
            noStore(response);
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(document.filename()).build().toString());
            response.setContentLengthLong(document.contentLength());
            Files.copy(document.path(), response.getOutputStream());
        }
    }

    private static ReportQuery query(MultiValueMap<String, String> parameters) {
        var values = ReportWebParameters.singleValues(parameters);
        return ReportQuery.parse(values.remove("type"), values);
    }

    private static String form(Map<String, String> parameters, Model model, HttpServletResponse response,
                               String error) {
        ReportType type = ReportType.CHARGES;
        String rawType = parameters.get("type");
        if (rawType != null && !rawType.isBlank()) {
            try {
                type = ReportType.valueOf(rawType.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                if (error == null) error = "Выберите один из четырёх видов отчёта.";
            }
        }
        var today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        model.addAttribute("types", ReportType.values());
        model.addAttribute("selectedType", type);
        model.addAttribute("month", parameters.getOrDefault("month", YearMonth.from(today).toString()));
        model.addAttribute("asOf", parameters.getOrDefault("asOf", today.toString()));
        model.addAttribute("from", parameters.getOrDefault("from", today.withDayOfMonth(1).toString()));
        model.addAttribute("to", parameters.getOrDefault("to", today.toString()));
        model.addAttribute("clientId", parameters.getOrDefault("clientId", ""));
        model.addAttribute("status", parameters.getOrDefault("status", "").strip().toUpperCase(Locale.ROOT));
        model.addAttribute("size", parameters.getOrDefault("size", "20"));
        if (error != null) {
            model.addAttribute("formError", error);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
        noStore(response);
        return "reports/parameters";
    }

    private static String link(HttpServletRequest request, String path, ReportQuery query, Integer page) {
        var url = UriComponentsBuilder.fromPath(request.getContextPath() + path);
        if (!path.startsWith("/api/")) url.queryParam("type", query.type().name());
        ReportWebParameters.filters(query).forEach((key, value) -> url.queryParam(key, value));
        if (page != null) url.queryParam("page", page);
        if (!path.equals("/reports.csv")) url.queryParam("size", query.size());
        return url.build().toUriString();
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
    }
}
