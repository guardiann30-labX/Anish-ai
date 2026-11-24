const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write conversations or messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/conversations`).get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_1").set({
      userId: ALICE_UID,
      title: "Hack",
      lastMessagePreview: "hi",
      modelMode: "FAST",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/conversations/conv_1/messages`).get()
  );
});

test("Owner can create, list, update, and delete conversations mirroring Kotlin payloads", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_1").set({
      userId: ALICE_UID,
      title: "Explain quantum tunneling",
      lastMessagePreview: "Explain quantum tunneling",
      modelMode: "DEEP_THINK",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).orderBy("updatedAt", "desc").get()
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_1").update({
      lastMessagePreview: "Quantum tunneling happens when...",
      modelMode: "DEEP_THINK",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_1").update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field / @DocumentId 'id' field
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_2").set({
      id: "conv_2",
      userId: ALICE_UID,
      title: "Ghost",
      lastMessagePreview: "",
      modelMode: "FAST",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_1").delete()
  );
});

test("Owner can create and read messages with and without optional thinkingContent", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // User message without thinkingContent
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations/conv_1/messages`).doc("msg_1").set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "USER",
      content: "Write a Kotlin coroutine retry helper",
      modelMode: "FAST",
      hasImage: false,
      createdAt: ts,
    })
  );

  // Assistant message with thinkingContent
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations/conv_1/messages`).doc("msg_2").set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "ASSISTANT",
      content: "Here is a clean exponential backoff retry helper...",
      thinkingContent: "Consider CancellationException propagation and exponential delay.",
      modelMode: "DEEP_THINK",
      hasImage: false,
      createdAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/conversations/conv_1/messages`).orderBy("createdAt", "asc").get()
  );

  // Messages are immutable
  await assertFails(
    aliceDb.collection(`users/${ALICE_UID}/conversations/conv_1/messages`).doc("msg_1").update({
      content: "Tampered",
    })
  );
});

test("Cross-user isolation prevents Bob from reading or writing Alice's conversations or messages", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const ts = new Date(Date.now() - 5000);
    await db.collection(`users/${ALICE_UID}/conversations`).doc("conv_alice").set({
      userId: ALICE_UID,
      title: "Private chat",
      lastMessagePreview: "Secret",
      modelMode: "FAST",
      createdAt: ts,
      updatedAt: ts,
    });
    await db.collection(`users/${ALICE_UID}/conversations/conv_alice/messages`).doc("msg_a").set({
      userId: ALICE_UID,
      conversationId: "conv_alice",
      role: "USER",
      content: "Secret prompt",
      modelMode: "FAST",
      hasImage: false,
      createdAt: ts,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/conversations`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_alice").get());
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/conversations`).doc("conv_alice").update({
      title: "Hijacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/conversations/conv_alice/messages`).get()
  );
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/conversations/conv_alice/messages`).doc("msg_spoof").set({
      userId: ALICE_UID,
      conversationId: "conv_alice",
      role: "USER",
      content: "Spoof",
      modelMode: "FAST",
      hasImage: false,
      createdAt: new Date(Date.now() - 1000),
    })
  );
});
