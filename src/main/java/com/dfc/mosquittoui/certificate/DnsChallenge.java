package com.dfc.mosquittoui.certificate;

/** Sets the TXT records of a DNS-01 challenge. */
interface DnsChallenge {

    /** @param domain the name without _acme-challenge and without a trailing dot, e.g. example.com for *.example.com */
    void set(String domain, String value) throws Exception;

    /** The challenge records were used, whether the validation succeeded or not. */
    void clear(String domain) throws Exception;
}
