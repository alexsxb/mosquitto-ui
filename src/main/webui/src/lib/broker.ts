import { $api } from "@/api/client"

export function useBrokerStatus() {
  return $api.useQuery("get", "/api/broker/status", {}, { refetchInterval: 10_000 })
}
