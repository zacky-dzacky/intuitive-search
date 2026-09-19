import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // This project sits inside the intuitive_search repo, which has its own
  // lockfiles; pin the trace root so `standalone` traces from here.
  outputFileTracingRoot: import.meta.dirname,
  // `pg` opens raw sockets; keep it out of any bundling attempt.
  serverExternalPackages: ["pg"],
  // The container runs `node server.js` from a slim image.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
};

export default nextConfig;
