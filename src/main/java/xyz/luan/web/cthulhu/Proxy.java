package xyz.luan.web.cthulhu;

import com.google.appengine.tools.cloudstorage.*;
import com.google.gson.Gson;
import xyz.luan.facade.Base64;
import xyz.luan.facade.HttpFacade;
import xyz.luan.facade.Util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.util.Enumeration;

import static com.google.common.io.ByteStreams.copy;

public class Proxy extends BaseServlet {

    private static final String BUCKET = "cthulhu-call.appspot.com";
    private static final int MAX_FILENAME_SIZE = 12;

    private static class Request {
        String contentType;
        InputStream is;

        Request(URLConnection c) throws IOException {
            this.is = c.getInputStream();
            this.contentType = c.getHeaderField("Content-Type");
        }

        Request(InputStream is, String contentType) {
            this.is = is;
            this.contentType = contentType;
        }

        void response(HttpServletResponse resp) throws IOException {
            resp.setContentType(contentType);
            copy(is, resp.getOutputStream());
        }
    }

    @Override
    public void process(String method, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uuid = extractUuid(request);
        boolean reset = extractReset(request);

        if (reset) {
            resetAllFilesFrom(uuid);
        } else {
            req(uuid, method, request).response(response);
        }
    }

    private boolean extractReset(HttpServletRequest request) {
        String reset = request.getHeader("cthulhu-reset");
        return reset != null && "true".equalsIgnoreCase(reset);
    }

    private String extractUuid(HttpServletRequest request) {
        String uuid = request.getHeader("cthulhu-uuid");
        if (uuid == null || !uuid.matches("[a-zA-Z0-9\\-]*")) {
            throw new RuntimeException("\"Invalid uuid header; must exist and match [a-zA-Z0-9\\\\-]*\"");
        }
        return uuid;
    }

    private String extractActualURL(HttpServletRequest request) {
        String domain = request.getHeader("cthulhu-domain"); // actually its protocol, subdomain, domain and port
        return domain + request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    }

    private void resetAllFilesFrom(String uuid) {
        deleteFolder(BUCKET, uuid);
    }

    private static void deleteFolder(String bucket, String folderName) {
        GcsService gcsService = GcsServiceFactory.createGcsService();
        try {
            ListResult list = gcsService.list(bucket, new ListOptions.Builder().setPrefix(folderName).setRecursive(true).build());

            while (list.hasNext()) {
                ListItem item = list.next();
                gcsService.delete(new GcsFilename(bucket, item.getName()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Request req(String uuid, String method, HttpServletRequest request) throws IOException {
        HttpFacade facade = facadeRequest(method, request);
        String key = extractKey(facade);

        GcsFilename fileName = new GcsFilename(BUCKET, uuid + "/" + key);
        GcsService gcsService = GcsServiceFactory.createGcsService();
        GcsFileMetadata metadata = gcsService.getMetadata(fileName);

        if (metadata == null) {
            Request req = new Request(facade.generateConnection());
            writeFile(fileName, gcsService, req);
            return req;
        } else {
            GcsInputChannel inputChannel = gcsService.openReadChannel(fileName, 0);
            return new Request(Channels.newInputStream(inputChannel), metadata.getOptions().getMimeType());
        }
    }

    private String extractKey(HttpFacade facade) {
        String facadeId = facade.getBaseUrl() + Base64.encode(new Gson().toJson(facade).getBytes());
        return facadeId.length() > MAX_FILENAME_SIZE ? facadeId.substring(0, MAX_FILENAME_SIZE) : facadeId;
    }

    private HttpFacade facadeRequest(String method, HttpServletRequest request) throws IOException {
        String path = extractActualURL(request);
        System.out.println("PATH: " + path);

        HttpFacade facade = new HttpFacade(path).method(method.toUpperCase());
        Enumeration headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String header = headerNames.nextElement().toString();
            if (!header.startsWith("cthulhu-") && !header.equalsIgnoreCase("host")) {
                facade.header(header, request.getHeader(header));
            }
        }
        String body = Util.toString(request.getInputStream());
        if (!body.isEmpty()) {
            facade.body(body);
        }
        facade.timeout(30000);
        return facade;
    }

    private void writeFile(GcsFilename fileName, GcsService gcsService, Request req) throws IOException {
        GcsFileOptions options = new GcsFileOptions.Builder().mimeType(req.contentType).build();
        GcsOutputChannel outputChannel = gcsService.createOrReplace(fileName, options);
        copy(req.is, Channels.newOutputStream(outputChannel));
        req.is.reset();
        outputChannel.close();
    }
}

