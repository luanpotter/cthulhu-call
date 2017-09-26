package xyz.luan.web.cthulhu;

import com.google.appengine.tools.cloudstorage.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;

import static com.google.common.io.ByteStreams.copy;

public class Proxy extends HttpServlet {

    private static final String BUCKET = "cthulhu-call.appspot.com";

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
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uuid = request.getHeader("cthulhu-uuid");
        if (uuid == null || !uuid.matches("[a-zA-Z0-9\\-]*")) {
            response.setStatus(460);
            response.getWriter().write("Invalid uuid header; must exist and match [a-zA-Z0-9\\-]*");
            response.getWriter().close();
        } else {
            String reset = request.getHeader("cthulhu-reset");
            if (reset != null && "true".equalsIgnoreCase(reset)) {
                resetAllFilesFrom(uuid);
            } else {
                String path = extractActualURL(request);
                req(uuid, path).response(response);
            }
        }
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
            //Error handling
        }
    }

    private URLConnection getUrlConnection(String path) throws IOException {
        URLConnection url = new URL(path).openConnection();
        url.setConnectTimeout(30000);
        return url;
    }

    private Request req(String uuid, String path) throws IOException {
        GcsFilename fileName = new GcsFilename(BUCKET, uuid + "/" + path);
        GcsService gcsService = GcsServiceFactory.createGcsService();
        GcsFileMetadata metadata = gcsService.getMetadata(fileName);

        if (metadata == null) {
            Request req = new Request(getUrlConnection(path));
            writeFile(fileName, gcsService, req);
            return req;
        } else {
            GcsInputChannel inputChannel = gcsService.openReadChannel(fileName, 0);
            return new Request(Channels.newInputStream(inputChannel), metadata.getOptions().getMimeType());
        }
    }

    private void writeFile(GcsFilename fileName, GcsService gcsService, Request req) throws IOException {
        GcsFileOptions options = new GcsFileOptions.Builder().mimeType(req.contentType).build();
        GcsOutputChannel outputChannel = gcsService.createOrReplace(fileName, options);
        copy(req.is, Channels.newOutputStream(outputChannel));
        req.is.reset();
        outputChannel.close();
    }
}

