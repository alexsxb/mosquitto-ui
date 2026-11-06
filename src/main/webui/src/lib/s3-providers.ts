/**
 * Known S3 providers: endpoints for the autocomplete of the endpoint field, regions for the region selection. With a
 * template, choosing a region also sets the endpoint (s3.eu-central-1.amazonaws.com and the like).
 */
export type S3Provider = {
  name: string;
  /** Endpoint for a region; {region} is replaced. */
  template: string;
  regions: { id: string; label?: string }[];
};

export const s3Providers: S3Provider[] = [
  {
    name: "Amazon S3",
    template: "s3.{region}.amazonaws.com",
    regions: [
      { id: "us-east-1", label: "N. Virginia" },
      { id: "us-east-2", label: "Ohio" },
      { id: "us-west-1", label: "N. California" },
      { id: "us-west-2", label: "Oregon" },
      { id: "af-south-1", label: "Cape Town" },
      { id: "ap-east-1", label: "Hong Kong" },
      { id: "ap-south-1", label: "Mumbai" },
      { id: "ap-south-2", label: "Hyderabad" },
      { id: "ap-southeast-1", label: "Singapore" },
      { id: "ap-southeast-2", label: "Sydney" },
      { id: "ap-southeast-3", label: "Jakarta" },
      { id: "ap-southeast-4", label: "Melbourne" },
      { id: "ap-southeast-5", label: "Malaysia" },
      { id: "ap-southeast-7", label: "Thailand" },
      { id: "ap-northeast-1", label: "Tokyo" },
      { id: "ap-northeast-2", label: "Seoul" },
      { id: "ap-northeast-3", label: "Osaka" },
      { id: "ca-central-1", label: "Canada Central" },
      { id: "ca-west-1", label: "Calgary" },
      { id: "eu-central-1", label: "Frankfurt" },
      { id: "eu-central-2", label: "Zurich" },
      { id: "eu-west-1", label: "Ireland" },
      { id: "eu-west-2", label: "London" },
      { id: "eu-west-3", label: "Paris" },
      { id: "eu-north-1", label: "Stockholm" },
      { id: "eu-south-1", label: "Milan" },
      { id: "eu-south-2", label: "Spain" },
      { id: "il-central-1", label: "Tel Aviv" },
      { id: "me-south-1", label: "Bahrain" },
      { id: "me-central-1", label: "UAE" },
      { id: "mx-central-1", label: "Mexico" },
      { id: "sa-east-1", label: "São Paulo" },
    ],
  },
  {
    name: "Hetzner",
    template: "{region}.your-objectstorage.com",
    regions: [
      { id: "fsn1", label: "Falkenstein" },
      { id: "nbg1", label: "Nuremberg" },
      { id: "hel1", label: "Helsinki" },
    ],
  },
  {
    name: "OVHcloud",
    template: "s3.{region}.io.cloud.ovh.net",
    regions: [
      { id: "gra", label: "Gravelines" },
      { id: "sbg", label: "Strasbourg" },
      { id: "rbx", label: "Roubaix" },
      { id: "de", label: "Frankfurt" },
      { id: "uk", label: "London" },
      { id: "waw", label: "Warsaw" },
      { id: "bhs", label: "Beauharnois" },
    ],
  },
  {
    name: "IONOS",
    template: "s3.{region}.ionoscloud.com",
    regions: [
      { id: "eu-central-1", label: "Frankfurt" },
      { id: "eu-central-2", label: "Berlin" },
      { id: "eu-south-2", label: "Logroño" },
    ],
  },
  {
    name: "Scaleway",
    template: "s3.{region}.scw.cloud",
    regions: [
      { id: "fr-par", label: "Paris" },
      { id: "nl-ams", label: "Amsterdam" },
      { id: "pl-waw", label: "Warsaw" },
    ],
  },
  {
    name: "Wasabi",
    template: "s3.{region}.wasabisys.com",
    regions: [
      { id: "us-east-1" },
      { id: "us-east-2" },
      { id: "us-central-1" },
      { id: "us-west-1" },
      { id: "ca-central-1" },
      { id: "eu-central-1" },
      { id: "eu-central-2" },
      { id: "eu-west-1" },
      { id: "eu-west-2" },
      { id: "ap-northeast-1" },
      { id: "ap-northeast-2" },
      { id: "ap-southeast-1" },
      { id: "ap-southeast-2" },
    ],
  },
  {
    name: "Backblaze B2",
    template: "s3.{region}.backblazeb2.com",
    regions: [
      { id: "us-west-001" },
      { id: "us-west-002" },
      { id: "us-west-004" },
      { id: "us-east-005" },
      { id: "eu-central-003" },
    ],
  },
  {
    name: "DigitalOcean Spaces",
    template: "{region}.digitaloceanspaces.com",
    regions: [
      { id: "nyc3" },
      { id: "sfo3" },
      { id: "atl1" },
      { id: "tor1" },
      { id: "ams3" },
      { id: "fra1" },
      { id: "lon1" },
      { id: "sgp1" },
      { id: "blr1" },
      { id: "syd1" },
    ],
  },
];

function pattern(provider: S3Provider) {
  const [before, after] = provider.template
    .split("{region}")
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  return new RegExp(`^(?:https?://)?${before}([a-z0-9-]+)${after}/?$`, "i");
}

/** Provider and region of an endpoint; an empty endpoint counts as Amazon S3 (the default of the region). */
export function s3ProviderOf(
  endpoint: string,
): { provider: S3Provider; region: string | null } | null {
  if (!endpoint.trim()) {
    return { provider: s3Providers[0], region: null };
  }
  for (const provider of s3Providers) {
    const match = pattern(provider).exec(endpoint.trim());
    if (match) {
      return { provider, region: match[1].toLowerCase() };
    }
  }
  return null;
}

export function s3Endpoint(provider: S3Provider, region: string) {
  return provider.template.replace("{region}", region);
}

/** All endpoints for the autocomplete, with the provider and the location as label. */
export const s3Endpoints = s3Providers.flatMap((provider) =>
  provider.regions.map((region) => ({
    value: s3Endpoint(provider, region.id),
    label: `${provider.name} · ${region.label ? `${region.label} (${region.id})` : region.id}`,
  })),
);
