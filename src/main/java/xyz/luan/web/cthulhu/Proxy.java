package xyz.luan.web.cthulhu;

import com.google.appengine.api.datastore.*;
import com.google.appengine.tools.cloudstorage.*;
import com.google.gson.Gson;
import xyz.luan.facade.Base64;
import xyz.luan.facade.HttpFacade;
import xyz.luan.facade.Util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.google.common.io.ByteStreams.copy;

public class Proxy extends BaseServlet {

    private DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
    private GcsService gcsService = GcsServiceFactory.createGcsService();

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
        deleteFileRefs(uuid);
    }

    private void deleteFileRefs(String uuid) {
        Query.Filter filter = new Query.FilterPredicate("uuid", Query.FilterOperator.EQUAL, uuid);
        List<Entity> files = datastore.prepare(new Query("file").setFilter(filter)).asList(FetchOptions.Builder.withDefaults());
        List<Key> ids = files.stream().map(Entity::getKey).collect(Collectors.toList());
        datastore.delete(ids);
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

        Entity fileRef = fetchFileRef(uuid, key);
        if (fileRef == null) {
            String fileId = UUID.randomUUID().toString();
            GcsFilename fileName = generateFileName(uuid, fileId);

            Request req = new Request(facade.generateConnection());

            writeFile(fileName, req);
            createFileRef(uuid, key, fileId);

            return req;
        } else {
            String fileId = fileRef.getProperty("fileId").toString();
            GcsFilename fileName = generateFileName(uuid, fileId);
            GcsFileMetadata metadata = gcsService.getMetadata(fileName);
            GcsInputChannel inputChannel = gcsService.openReadChannel(fileName, 0);
            return new Request(Channels.newInputStream(inputChannel), metadata.getOptions().getMimeType());
        }
    }

    private GcsFilename generateFileName(String uuid, String fileId) {
        return new GcsFilename(BUCKET, uuid + "/" + fileId);
    }

    private void createFileRef(String uuid, String key, String fileId) {
        Entity newFileRef = new Entity("file", fileId);
        newFileRef.setProperty("fileId", fileId);
        newFileRef.setProperty("key", new Text(key));
        newFileRef.setProperty("uuid", uuid);
        datastore.put(newFileRef);
    }

    private Entity fetchFileRef(String uuid, String key) {
        Query.Filter byKey = new Query.FilterPredicate("key", Query.FilterOperator.EQUAL, key);
        Query.Filter byUuid = new Query.FilterPredicate("uuid", Query.FilterOperator.EQUAL, uuid);
        Query.Filter combination = new Query.CompositeFilter(Query.CompositeFilterOperator.AND, Arrays.asList(byKey, byUuid));
        return datastore.prepare(new Query("file").setFilter(combination)).asSingleEntity();
    }

    private String extractKey(HttpFacade facade) {
        return facade.getBaseUrl() + "::" + Base64.encode(new Gson().toJson(facade).getBytes());
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

    private void writeFile(GcsFilename fileName, Request req) throws IOException {
        GcsFileOptions options = new GcsFileOptions.Builder().mimeType(req.contentType).build();
        GcsOutputChannel outputChannel = gcsService.createOrReplace(fileName, options);

        byte[] bytes = toBytes(req.is);

        req.is = new ByteArrayInputStream(bytes);

        copy(new ByteArrayInputStream(bytes), Channels.newOutputStream(outputChannel));
        outputChannel.close();
    }

    private byte[] toBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(stream, baos);
        return baos.toByteArray();
    }
}

