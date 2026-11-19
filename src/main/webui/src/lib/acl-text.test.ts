import assert from "node:assert/strict"
import { describe, test } from "node:test"

import { diffAcls, normalizeAcls, parseAclText, serializeAcls, textDiffers, topicProblem, type AclRule } from "./acl-text.ts"

/** Rules without their line numbers. */
function rules(text: string, defaults: string[] = ["sub"]) {
  const result = parseAclText(text, defaults)
  assert.deepEqual(result.errors, [])
  return result.rules.map(({ acltype, topic, priority, allow }) => ({ acltype, topic, priority, allow }))
}

const allow = (acltype: string, topic: string, priority = 0): AclRule => ({ acltype, topic, priority, allow: true })
const deny = (acltype: string, topic: string, priority = 0): AclRule => ({ acltype, topic, priority, allow: false })

describe("parseAclText", () => {
  test("plain topics get the default actions", () => {
    assert.deepEqual(rules("a/b\nc/+/d\n", ["sub", "recv"]), [
      allow("subscribeLiteral", "a/b"),
      allow("publishClientReceive", "a/b"),
      allow("subscribePattern", "c/+/d"),
      allow("publishClientReceive", "c/+/d"),
    ])
  })

  test("defaults may be full rule types", () => {
    assert.deepEqual(rules("a/#", ["subscribeLiteral"]), [allow("subscribeLiteral", "a/#")])
  })

  test("shorthands, effect and priority anywhere in the line", () => {
    assert.deepEqual(rules("pub a\nsub home/#\nPUBSUB x\ndeny pub secret prio 10\nsecret2 prio=-3 deny Recv"), [
      allow("publishClientSend", "a"),
      allow("subscribePattern", "home/#"),
      allow("publishClientSend", "x"),
      allow("publishClientReceive", "x"),
      allow("subscribeLiteral", "x"),
      deny("publishClientSend", "secret", 10),
      deny("publishClientReceive", "secret2", -3),
    ])
  })

  test("full rule names", () => {
    assert.deepEqual(rules("subscribeLiteral a/#\nunsubscribePattern b"), [allow("subscribeLiteral", "a/#"), allow("unsubscribePattern", "b")])
  })

  test("comments, blank lines and bullets", () => {
    assert.deepEqual(rules("# comment\n// another\n\n  - a\n* b\n• c\n1. d\n2) e\n#"), [
      allow("subscribeLiteral", "a"),
      allow("subscribeLiteral", "b"),
      allow("subscribeLiteral", "c"),
      allow("subscribeLiteral", "d"),
      allow("subscribeLiteral", "e"),
      allow("subscribePattern", "#"),
    ])
  })

  test("commas and semicolons separate rules, which take the keywords before them", () => {
    assert.deepEqual(rules("a, b; c"), [allow("subscribeLiteral", "a"), allow("subscribeLiteral", "b"), allow("subscribeLiteral", "c")])
    assert.deepEqual(rules("pub a, b, sub c, d"), [
      allow("publishClientSend", "a"),
      allow("publishClientSend", "b"),
      allow("subscribeLiteral", "c"),
      allow("subscribeLiteral", "d"),
    ])
    assert.deepEqual(rules("a, b\nc", ["pub"]), [allow("publishClientSend", "a"), allow("publishClientSend", "b"), allow("publishClientSend", "c")])
  })

  test("tabs as from spreadsheets", () => {
    assert.deepEqual(rules("pub\tdevices/x\tprio\t5"), [allow("publishClientSend", "devices/x", 5)])
  })

  test("topics with spaces, with and without quotes", () => {
    assert.deepEqual(rules("pub my topic/x"), [allow("publishClientSend", "my topic/x")])
    assert.deepEqual(rules('pub "a, b; \\"c\\"" prio 2'), [allow("publishClientSend", 'a, b; "c"', 2)])
    assert.deepEqual(rules('"pub"\n"deny"'), [allow("subscribeLiteral", "pub"), allow("subscribeLiteral", "deny")])
  })

  test("lines of Mosquitto ACL files", () => {
    assert.deepEqual(rules("topic read a/#\ntopic write b\ntopic c\ntopic deny d", ["pub"]), [
      allow("publishClientReceive", "a/#"),
      allow("subscribePattern", "a/#"),
      allow("publishClientSend", "b"),
      allow("publishClientSend", "c"),
      allow("publishClientReceive", "c"),
      allow("subscribeLiteral", "c"),
      deny("publishClientSend", "d", 10),
      deny("publishClientReceive", "d", 10),
      deny("subscribeLiteral", "d", 10),
    ])
    // a topic named topic
    assert.deepEqual(rules("topic"), [allow("subscribeLiteral", "topic")])
  })

  test("duplicates count once", () => {
    assert.deepEqual(rules("a\na\nsub a"), [allow("subscribeLiteral", "a")])
  })

  test("errors name their line", () => {
    const { rules: parsed, errors } = parseAclText("ok\nsub a/#/b\npub x prio\npub\nuser bob\npattern read %c/#\nfoo/bar+\npub a prio 1\npub a prio 2", ["sub"])
    assert.deepEqual(
      parsed.map((rule) => [rule.line, rule.topic]),
      [
        [1, "ok"],
        [8, "a"],
      ],
    )
    assert.deepEqual(errors, [
      { line: 2, code: "invalidTopic", topic: "a/#/b", reason: "hashNotLast" },
      { line: 3, code: "badPriority", value: "" },
      { line: 4, code: "noTopic" },
      { line: 5, code: "unsupported", keyword: "user" },
      { line: 6, code: "unsupported", keyword: "pattern" },
      { line: 7, code: "invalidTopic", topic: "foo/bar+", reason: "wildcardInLevel" },
      { line: 9, code: "conflict", other: 8, acltype: "publishClientSend", topic: "a" },
    ])
  })

  test("without defaults a plain topic is an error", () => {
    assert.deepEqual(parseAclText("a", []).errors, [{ line: 1, code: "noAction" }])
  })
})

describe("topicProblem", () => {
  test("valid filters", () => {
    for (const topic of ["#", "+", "a/+/b", "a/#", "/", "a//b", "$SYS/#", "+/+"]) {
      assert.equal(topicProblem(topic), null, topic)
    }
  })

  test("invalid filters", () => {
    assert.equal(topicProblem("a#"), "wildcardInLevel")
    assert.equal(topicProblem("a/b+/c"), "wildcardInLevel")
    assert.equal(topicProblem("#/a"), "hashNotLast")
    assert.equal(topicProblem("a\0b"), "nullChar")
    assert.equal(topicProblem("x".repeat(65536)), "tooLong")
  })
})

describe("serializeAcls", () => {
  const acls = [
    allow("publishClientSend", "app/chat"),
    allow("publishClientReceive", "app/chat"),
    allow("subscribeLiteral", "app/chat"),
    allow("subscribePattern", "home/#"),
    allow("publishClientReceive", "home/#"),
    allow("subscribeLiteral", "literal/#"),
    deny("publishClientSend", "home/secret", 10),
    allow("unsubscribePattern", "x/+"),
    allow("publishClientSend", "my topic, odd"),
    allow("subscribeLiteral", "deny"),
    allow("publishClientSend", "pubsub", -1),
  ]

  test("compact and aligned", () => {
    assert.equal(
      serializeAcls(acls),
      [
        "deny pub         home/secret prio 10",
        "pubsub           app/chat",
        'sub              "deny"',
        "recv sub         home/#",
        "subscribeLiteral literal/#",
        'pub              "my topic, odd"',
        "unsub            x/+",
        'pub              "pubsub" prio -1',
      ].join("\n"),
    )
  })

  test("reads back to the same rules whatever the defaults", () => {
    for (const defaults of [[], ["pubsub"], ["unsub"]]) {
      const back = parseAclText(serializeAcls(acls), defaults)
      assert.deepEqual(back.errors, [])
      assert.deepEqual(diffAcls(acls, back.rules), { added: [], removed: [], changed: [], unchanged: acls.length })
    }
  })

  test("nothing for no rules", () => {
    assert.equal(serializeAcls([]), "")
  })
})

describe("diffAcls", () => {
  test("added, removed, changed", () => {
    const current = [allow("publishClientSend", "a"), allow("subscribeLiteral", "b"), allow("subscribeLiteral", "c")]
    const next = [allow("publishClientSend", "a"), deny("subscribeLiteral", "b", 5), allow("publishClientSend", "d")]
    assert.deepEqual(diffAcls(current, next), {
      added: [allow("publishClientSend", "d")],
      removed: [allow("subscribeLiteral", "c")],
      changed: [{ before: allow("subscribeLiteral", "b"), after: deny("subscribeLiteral", "b", 5) }],
      unchanged: 1,
    })
  })
})

describe("textDiffers", () => {
  const acls = [allow("subscribePattern", "home/#"), deny("publishClientSend", "home/secret", 10)]

  test("no text or the rules as they are", () => {
    assert.equal(textDiffers(null, acls), false)
    assert.equal(textDiffers(serializeAcls(acls), acls), false)
    assert.equal(textDiffers("\n" + serializeAcls(acls) + "\n\n", acls), false)
  })

  test("anything else, also a text with errors", () => {
    assert.equal(textDiffers(serializeAcls(acls) + "\npub x", acls), true)
    assert.equal(textDiffers(serializeAcls(acls) + "\nsub a/#/b", acls), true)
    assert.equal(textDiffers("", acls), true)
  })
})

describe("normalizeAcls", () => {
  test("what the API leaves out", () => {
    assert.deepEqual(normalizeAcls([{ acltype: "publishClientSend", topic: "a" }, { acltype: "subscribeLiteral", topic: "b", priority: 3, allow: true }]), [
      deny("publishClientSend", "a"),
      allow("subscribeLiteral", "b", 3),
    ])
  })
})
