import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

export default defineConfig({
    plugins: [scalaJSPlugin({cwd: '..', projectID: 'examplesJS'})],
    server: {
        proxy: {
            '/api': {
                target: 'http://localhost:8088',
                changeOrigin: true,
                rewrite: (path) => path.substring(4)
            }
        },
    },
});
