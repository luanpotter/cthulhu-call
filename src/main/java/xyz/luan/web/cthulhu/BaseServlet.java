package xyz.luan.web.cthulhu;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public abstract class BaseServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("get", req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("post", req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("put", req, resp);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("delete", req, resp);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("options", req, resp);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("head", req, resp);
    }

    @Override
    protected void doTrace(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        processSafe("trace", req, resp);
    }

    private void processSafe(String method, HttpServletRequest req, HttpServletResponse resp) {
        try {
            process(method, req, resp);
        } catch (Throwable t) {
            resp.setStatus(460);
            try (PrintWriter writer = resp.getWriter()) {
                writer.write(t.getMessage());
                handle(t);
            } catch (IOException ex) {
                handle(ex);
            }
        }

    }

    private void handle(Throwable t) {
        System.err.println("Error! " + t.getMessage());
        t.printStackTrace();
    }

    protected abstract void process(String method, HttpServletRequest req, HttpServletResponse resp) throws IOException;
}
