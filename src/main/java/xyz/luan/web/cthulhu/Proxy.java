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

    private static final String SUZY = "https://susy.ic.unicamp.br:9999";
    private static final String BUCKET = "susy-proxy.appspot.com";

    private static class Request {
        String contentType;
        InputStream is;

        public Request(URLConnection c) throws IOException {
            this.is = c.getInputStream();
            this.contentType = c.getHeaderField("Content-Type");
        }

        public Request(InputStream is, String contentType) {
            this.is = is;
            this.contentType = contentType;
        }

        public void response(HttpServletResponse resp) throws IOException {
            resp.setContentType(contentType);
            copy(is, resp.getOutputStream());
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = SUZY + request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        req(path).response(response);
    }

    private URLConnection getUrlConnection(String path) throws IOException {
        URLConnection url = new URL(path).openConnection();
        url.setConnectTimeout(30000);
        return url;
    }

    private Request req(String path) throws IOException {
        GcsFilename fileName = new GcsFilename(BUCKET, path);
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

