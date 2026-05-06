import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  define: {
    // Surfaced as a tiny "bXXXXXXX" string in the toolbar so we can verify
    // visually that the JCEF browser is rendering the freshly-built bundle
    // and not a cached one.
    __DBTREE_BUILD_ID__: JSON.stringify(
      new Date().toISOString().replace(/[-:T]/g, "").slice(2, 12),
    ),
  },
  plugins: [react()],
  // The plugin's JCEF resource handler maps known filenames to classpath streams,
  // so we need flat (hashless) bundle names. Standalone use does not need cache
  // busting either since the bundle is shipped with the plugin.
  base: "./",
  build: {
    assetsDir: "",
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: "index.js",
        chunkFileNames: "index.js",
        assetFileNames: "index[extname]",
      },
    },
  },
});
