import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `base` matches the GitHub Pages project path. Deep links and assets must both work under
// /oqthara/, so this is the single place that prefix is defined.
export default defineConfig({
  base: "/oqthara/",
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    // The site is small and prerendered, so a single stylesheet and one script chunk keep
    // the deployed output easy to inspect.
    cssCodeSplit: false,
  },
});
