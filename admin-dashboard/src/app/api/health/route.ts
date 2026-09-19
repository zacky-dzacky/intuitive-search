import { NextResponse } from "next/server";

import { systemHealth } from "@/lib/health";

export const dynamic = "force-dynamic";

export async function GET() {
  const services = await systemHealth();
  return NextResponse.json({
    ok: services.every((service) => service.ok),
    services,
  });
}
