package dev.oqthara.lsp;

import java.io.IOException;

/** Starts the Oqthara language server on stdin/stdout. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = new Server(System.in, System.out).run();
        } catch (IOException e) {
            System.err.println("oq-lsp: " + e.getMessage());
            status = 1;
        }
        System.exit(status);
    }
}
