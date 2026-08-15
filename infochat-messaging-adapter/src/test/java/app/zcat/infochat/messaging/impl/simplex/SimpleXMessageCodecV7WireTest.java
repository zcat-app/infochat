package app.zcat.infochat.messaging.impl.simplex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.zcat.infochat.messaging.FailureCategory;
import app.zcat.infochat.messaging.ScopeRef;

import org.junit.jupiter.api.Test;

/** M1-839: every depended text/group wire surface re-pinned to the bundled
 * simplex-chat v7.0.0 with frames captured 2026-08-15; capture method, trim
 * discipline, D37 substitutions, dispositions: M1-839 ticket evidence. */
class SimpleXMessageCodecV7WireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The bot identity's per-group memberId in the captured mention frame (D51 anchor). */
    private static final String BOT_MEMBER_ID = "bVJpU2x2Q2ozMmZkVE81WA==";

    // Direct-DM newChatItems captured from the v7.0.0 binary (2026-08-15,
    // loopback probe): plural async event, no corrId, chatInfo.type
    // "direct"; trims per the ticket record.
    private static final String V7_DIRECT_DM = """
{
          "resp": {
            "type": "newChatItems",
            "chatItems": [
              {
                "chatInfo": {
                  "type": "direct",
                  "contact": {
                    "contactId": 3,
                    "localDisplayName": "v7probe-user",
                    "profile": {
                      "profileId": 3,
                      "displayName": "v7probe-user",
                      "fullName": "",
                      "localAlias": ""
                    },
                    "createdAt": "2026-08-15T15:30:47.133956843Z",
                    "updatedAt": "2026-08-15T15:30:47.133956843Z"
                  }
                },
                "chatItem": {
                  "chatDir": {
                    "type": "directRcv"
                  },
                  "meta": {
                    "itemId": 8,
                    "itemTs": "2026-08-15T15:30:48Z",
                    "itemText": "v7 probe dm canary",
                    "itemStatus": {
                      "type": "rcvNew"
                    },
                    "itemSharedMsgId": "dXQ3UnhqVi9YSnhCTlhydg==",
                    "itemEdited": false,
                    "userMention": false,
                    "hasLink": false,
                    "deletable": false,
                    "editable": false,
                    "showGroupAsSender": false,
                    "createdAt": "2026-08-15T15:30:48.826924846Z",
                    "updatedAt": "2026-08-15T15:30:48.826924846Z"
                  },
                  "content": {
                    "type": "rcvMsgContent",
                    "msgContent": {
                      "type": "text",
                      "text": "v7 probe dm canary"
                    }
                  },
                  "mentions": {},
                  "reactions": []
                }
              }
            ]
          }
        }
            """;

    // Group message with a mentions{} envelope — v7.0.0-captured
    // (2026-08-15, loopback probe): a formatted bot @mention; the D51 anchor
    // byte-equals the {memberId, memberRef} value (memberRef: ignored bulk).
    private static final String V7_GROUP_MENTION = """
{
          "resp": {
            "type": "newChatItems",
            "chatItems": [
              {
                "chatInfo": {
                  "type": "group",
                  "groupInfo": {
                    "groupId": 1,
                    "useRelays": false,
                    "localDisplayName": "v7probe",
                    "groupProfile": {
                      "displayName": "v7probe",
                      "fullName": "",
                      "groupPreferences": {
                        "directMessages": {
                          "enable": "on"
                        },
                        "history": {
                          "enable": "on"
                        }
                      }
                    },
                    "localAlias": "",
                    "fullGroupPreferences": {
                      "timedMessages": {
                        "enable": "off",
                        "ttl": 86400
                      },
                      "directMessages": {
                        "enable": "on"
                      },
                      "fullDelete": {
                        "enable": "off"
                      },
                      "reactions": {
                        "enable": "on"
                      },
                      "voice": {
                        "enable": "on"
                      },
                      "files": {
                        "enable": "on"
                      },
                      "simplexLinks": {
                        "enable": "on"
                      },
                      "reports": {
                        "enable": "on"
                      },
                      "history": {
                        "enable": "on"
                      },
                      "support": {
                        "enable": "on"
                      },
                      "sessions": {
                        "enable": "off"
                      },
                      "comments": {
                        "enable": "off"
                      },
                      "signMessages": {
                        "enable": "off"
                      },
                      "commands": []
                    },
                    "membership": {
                      "groupMemberId": 2,
                      "groupId": 1,
                      "indexInGroup": 1,
                      "memberId": "bVJpU2x2Q2ozMmZkVE81WA==",
                      "memberRole": "member",
                      "memberCategory": "user",
                      "memberStatus": "connected",
                      "memberSettings": {
                        "showMessages": true
                      },
                      "blockedByAdmin": false,
                      "invitedBy": {
                        "type": "contact",
                        "byContactId": 3
                      },
                      "invitedByGroupMemberId": 1,
                      "localDisplayName": "v7probe-bot",
                      "memberProfile": {
                        "profileId": 1,
                        "displayName": "v7probe-bot",
                        "fullName": "",
                        "peerType": "bot",
                        "localAlias": ""
                      },
                      "memberContactId": 1,
                      "memberContactProfileId": 1,
                      "createdAt": "2026-08-15T15:30:50.26849145Z",
                      "updatedAt": "2026-08-15T15:30:50.816094482Z"
                    },
                    "createdAt": "2026-08-15T15:30:50.26849145Z",
                    "updatedAt": "2026-08-15T15:30:50.27052122Z",
                    "userMemberProfileSentAt": "2026-08-15T15:30:50.26849145Z",
                    "groupSummary": {
                      "currentMembers": 2
                    },
                    "membersRequireAttention": 0
                  }
                },
                "chatItem": {
                  "chatDir": {
                    "type": "groupRcv",
                    "groupMember": {
                      "groupMemberId": 1,
                      "groupId": 1,
                      "indexInGroup": 0,
                      "memberId": "WkdPVndUVUx1MU94SHg3NQ==",
                      "memberRole": "owner",
                      "memberCategory": "host",
                      "memberStatus": "connected",
                      "memberSettings": {
                        "showMessages": true
                      },
                      "blockedByAdmin": false,
                      "invitedBy": {
                        "type": "unknown"
                      },
                      "localDisplayName": "v7probe-user",
                      "memberProfile": {
                        "profileId": 3,
                        "displayName": "v7probe-user",
                        "fullName": "",
                        "localAlias": ""
                      },
                      "memberContactId": 3,
                      "memberContactProfileId": 3,
                      "createdAt": "2026-08-15T15:30:50.26849145Z",
                      "updatedAt": "2026-08-15T15:30:50.815930167Z"
                    }
                  },
                  "meta": {
                    "itemId": 25,
                    "itemTs": "2026-08-15T15:35:29Z",
                    "itemText": "v7 probe formatted mention canary @v7probe-bot tail",
                    "itemStatus": {
                      "type": "rcvNew"
                    },
                    "itemSharedMsgId": "U0kvaGJpUmlNbE05Mjd1NQ==",
                    "itemEdited": false,
                    "userMention": true,
                    "hasLink": false,
                    "deletable": false,
                    "editable": false,
                    "showGroupAsSender": false,
                    "createdAt": "2026-08-15T15:35:29.354702833Z",
                    "updatedAt": "2026-08-15T15:35:29.354702833Z"
                  },
                  "content": {
                    "type": "rcvMsgContent",
                    "msgContent": {
                      "type": "text",
                      "text": "v7 probe formatted mention canary @v7probe-bot tail"
                    }
                  },
                  "mentions": {
                    "v7probe-bot": {
                      "memberId": "bVJpU2x2Q2ozMmZkVE81WA==",
                      "memberRef": {
                        "groupMemberId": 2,
                        "displayName": "v7probe-bot",
                        "localAlias": "",
                        "memberRole": "member"
                      }
                    }
                  },
                  "formattedText": [
                    {
                      "text": "v7 probe formatted mention canary "
                    },
                    {
                      "format": {
                        "type": "mention",
                        "memberName": "v7probe-bot"
                      },
                      "text": "@v7probe-bot"
                    },
                    {
                      "text": " tail"
                    }
                  ],
                  "reactions": []
                }
              }
            ]
          }
        }
            """;

    // Send-ack newChatItems with corrId — v7.0.0-captured (2026-08-15,
    // loopback probe): the response to the bot's own /_send; item id at
    // chatItems[0].chatItem.meta.itemId.
    private static final String V7_SEND_ACK = """
{
          "corrId": "c6",
          "resp": {
            "type": "newChatItems",
            "chatItems": [
              {
                "chatInfo": {
                  "type": "direct",
                  "contact": {
                    "contactId": 3,
                    "localDisplayName": "v7probe-user",
                    "profile": {
                      "profileId": 3,
                      "displayName": "v7probe-user",
                      "fullName": "",
                      "localAlias": ""
                    },
                    "createdAt": "2026-08-15T15:30:47.133956843Z",
                    "updatedAt": "2026-08-15T15:30:47.133956843Z"
                  }
                },
                "chatItem": {
                  "chatDir": {
                    "type": "directSnd"
                  },
                  "meta": {
                    "itemId": 9,
                    "itemTs": "2026-08-15T15:30:49.019343656Z",
                    "itemText": "v7 probe reply canary",
                    "itemStatus": {
                      "type": "sndNew"
                    },
                    "itemSharedMsgId": "WmJvek5VcXFsNXdPMXZrZA==",
                    "itemEdited": false,
                    "userMention": false,
                    "hasLink": false,
                    "deletable": true,
                    "editable": true,
                    "showGroupAsSender": false,
                    "createdAt": "2026-08-15T15:30:49.019343656Z",
                    "updatedAt": "2026-08-15T15:30:49.019343656Z"
                  },
                  "content": {
                    "type": "sndMsgContent",
                    "msgContent": {
                      "type": "text",
                      "text": "v7 probe reply canary"
                    }
                  },
                  "mentions": {},
                  "reactions": []
                }
              }
            ]
          }
        }
            """;

    // receivedGroupInvitation — v7.0.0-captured (2026-08-15, loopback
    // probe): the inviter is at groupInfo.membership.invitedBy.byContactId
    // with invitedBy.type "contact" (D52).
    private static final String V7_GROUP_INVITATION = """
{
          "resp": {
            "type": "receivedGroupInvitation",
            "groupInfo": {
              "groupId": 1,
              "useRelays": false,
              "localDisplayName": "v7probe",
              "groupProfile": {
                "displayName": "v7probe",
                "fullName": "",
                "groupPreferences": {
                  "directMessages": {
                    "enable": "on"
                  },
                  "history": {
                    "enable": "on"
                  }
                }
              },
              "localAlias": "",
              "fullGroupPreferences": {
                "timedMessages": {
                  "enable": "off",
                  "ttl": 86400
                },
                "directMessages": {
                  "enable": "on"
                },
                "fullDelete": {
                  "enable": "off"
                },
                "reactions": {
                  "enable": "on"
                },
                "voice": {
                  "enable": "on"
                },
                "files": {
                  "enable": "on"
                },
                "simplexLinks": {
                  "enable": "on"
                },
                "reports": {
                  "enable": "on"
                },
                "history": {
                  "enable": "on"
                },
                "support": {
                  "enable": "on"
                },
                "sessions": {
                  "enable": "off"
                },
                "comments": {
                  "enable": "off"
                },
                "signMessages": {
                  "enable": "off"
                },
                "commands": []
              },
              "membership": {
                "groupMemberId": 2,
                "groupId": 1,
                "indexInGroup": 1,
                "memberId": "bVJpU2x2Q2ozMmZkVE81WA==",
                "memberRole": "member",
                "memberCategory": "user",
                "memberStatus": "invited",
                "memberSettings": {
                  "showMessages": true
                },
                "blockedByAdmin": false,
                "invitedBy": {
                  "type": "contact",
                  "byContactId": 3
                },
                "invitedByGroupMemberId": 1,
                "localDisplayName": "v7probe-bot",
                "memberProfile": {
                  "profileId": 1,
                  "displayName": "v7probe-bot",
                  "fullName": "",
                  "peerType": "bot",
                  "localAlias": ""
                },
                "memberContactId": 1,
                "memberContactProfileId": 1,
                "createdAt": "2026-08-15T15:30:50.26849145Z",
                "updatedAt": "2026-08-15T15:30:50.26849145Z"
              },
              "createdAt": "2026-08-15T15:30:50.26849145Z",
              "updatedAt": "2026-08-15T15:30:50.26849145Z",
              "userMemberProfileSentAt": "2026-08-15T15:30:50.26849145Z",
              "groupSummary": {
                "currentMembers": 2
              },
              "membersRequireAttention": 0
            },
            "contact": {
              "contactId": 3,
              "localDisplayName": "v7probe-user",
              "profile": {
                "profileId": 3,
                "displayName": "v7probe-user",
                "fullName": "",
                "localAlias": ""
              },
              "createdAt": "2026-08-15T15:30:47.133956843Z",
              "updatedAt": "2026-08-15T15:30:47.133956843Z"
            },
            "fromMemberRole": "owner",
            "memberRole": "member"
          }
        }
            """;

    // The /_join response pair, captured from the v7.0.0 binary (2026-08-15,
    // loopback probe): userAcceptedGroupSent + async userJoinedGroup — not
    // codec-consumed, must decode Ignored.
    private static final String V7_JOIN_RESPONSE = """
{
          "corrId": "c10",
          "resp": {
            "type": "userAcceptedGroupSent",
            "groupInfo": {
              "groupId": 1,
              "useRelays": false,
              "localDisplayName": "v7probe",
              "groupProfile": {
                "displayName": "v7probe",
                "fullName": "",
                "groupPreferences": {
                  "directMessages": {
                    "enable": "on"
                  },
                  "history": {
                    "enable": "on"
                  }
                }
              },
              "localAlias": "",
              "fullGroupPreferences": {
                "timedMessages": {
                  "enable": "off",
                  "ttl": 86400
                },
                "directMessages": {
                  "enable": "on"
                },
                "fullDelete": {
                  "enable": "off"
                },
                "reactions": {
                  "enable": "on"
                },
                "voice": {
                  "enable": "on"
                },
                "files": {
                  "enable": "on"
                },
                "simplexLinks": {
                  "enable": "on"
                },
                "reports": {
                  "enable": "on"
                },
                "history": {
                  "enable": "on"
                },
                "support": {
                  "enable": "on"
                },
                "sessions": {
                  "enable": "off"
                },
                "comments": {
                  "enable": "off"
                },
                "signMessages": {
                  "enable": "off"
                },
                "commands": []
              },
              "membership": {
                "groupMemberId": 2,
                "groupId": 1,
                "indexInGroup": 1,
                "memberId": "bVJpU2x2Q2ozMmZkVE81WA==",
                "memberRole": "member",
                "memberCategory": "user",
                "memberStatus": "accepted",
                "memberSettings": {
                  "showMessages": true
                },
                "blockedByAdmin": false,
                "invitedBy": {
                  "type": "contact",
                  "byContactId": 3
                },
                "invitedByGroupMemberId": 1,
                "localDisplayName": "v7probe-bot",
                "memberProfile": {
                  "profileId": 1,
                  "displayName": "v7probe-bot",
                  "fullName": "",
                  "peerType": "bot",
                  "localAlias": ""
                },
                "memberContactId": 1,
                "memberContactProfileId": 1,
                "createdAt": "2026-08-15T15:30:50.26849145Z",
                "updatedAt": "2026-08-15T15:30:50.26849145Z"
              },
              "createdAt": "2026-08-15T15:30:50.26849145Z",
              "updatedAt": "2026-08-15T15:30:50.27052122Z",
              "userMemberProfileSentAt": "2026-08-15T15:30:50.26849145Z",
              "groupSummary": {
                "currentMembers": 0
              },
              "membersRequireAttention": 0
            }
          }
        }
            """;

    private static final String V7_JOINED_ASYNC = """
{
          "resp": {
            "type": "userJoinedGroup",
            "groupInfo": {
              "groupId": 1,
              "useRelays": false,
              "localDisplayName": "v7probe",
              "groupProfile": {
                "displayName": "v7probe",
                "fullName": "",
                "groupPreferences": {
                  "directMessages": {
                    "enable": "on"
                  },
                  "history": {
                    "enable": "on"
                  }
                }
              },
              "localAlias": "",
              "fullGroupPreferences": {
                "timedMessages": {
                  "enable": "off",
                  "ttl": 86400
                },
                "directMessages": {
                  "enable": "on"
                },
                "fullDelete": {
                  "enable": "off"
                },
                "reactions": {
                  "enable": "on"
                },
                "voice": {
                  "enable": "on"
                },
                "files": {
                  "enable": "on"
                },
                "simplexLinks": {
                  "enable": "on"
                },
                "reports": {
                  "enable": "on"
                },
                "history": {
                  "enable": "on"
                },
                "support": {
                  "enable": "on"
                },
                "sessions": {
                  "enable": "off"
                },
                "comments": {
                  "enable": "off"
                },
                "signMessages": {
                  "enable": "off"
                },
                "commands": []
              },
              "membership": {
                "groupMemberId": 2,
                "groupId": 1,
                "indexInGroup": 1,
                "memberId": "bVJpU2x2Q2ozMmZkVE81WA==",
                "memberRole": "member",
                "memberCategory": "user",
                "memberStatus": "connected",
                "memberSettings": {
                  "showMessages": true
                },
                "blockedByAdmin": false,
                "invitedBy": {
                  "type": "contact",
                  "byContactId": 3
                },
                "invitedByGroupMemberId": 1,
                "localDisplayName": "v7probe-bot",
                "memberProfile": {
                  "profileId": 1,
                  "displayName": "v7probe-bot",
                  "fullName": "",
                  "peerType": "bot",
                  "localAlias": ""
                },
                "memberContactId": 1,
                "memberContactProfileId": 1,
                "createdAt": "2026-08-15T15:30:50.26849145Z",
                "updatedAt": "2026-08-15T15:30:50.278541465Z"
              },
              "createdAt": "2026-08-15T15:30:50.26849145Z",
              "updatedAt": "2026-08-15T15:30:50.27052122Z",
              "userMemberProfileSentAt": "2026-08-15T15:30:50.26849145Z",
              "groupSummary": {
                "currentMembers": 2
              },
              "membersRequireAttention": 0
            },
            "hostMember": {
              "groupMemberId": 1,
              "groupId": 1,
              "indexInGroup": 0,
              "memberId": "WkdPVndUVUx1MU94SHg3NQ==",
              "memberRole": "owner",
              "memberCategory": "host",
              "memberStatus": "connected",
              "memberSettings": {
                "showMessages": true
              },
              "blockedByAdmin": false,
              "invitedBy": {
                "type": "unknown"
              },
              "localDisplayName": "v7probe-user",
              "memberProfile": {
                "profileId": 3,
                "displayName": "v7probe-user",
                "fullName": "",
                "localAlias": ""
              },
              "memberContactId": 3,
              "memberContactProfileId": 3,
              "createdAt": "2026-08-15T15:30:50.26849145Z",
              "updatedAt": "2026-08-15T15:30:50.278398419Z"
            }
          }
        }
            """;

    // userContactLink (/show_address) — v7.0.0-captured (2026-08-15,
    // loopback probe); D37 — the two link values are same-grammar synthetic
    // substitutions, the corrId is the capture's own.
    private static final String V7_USER_CONTACT_LINK = """
{
          "corrId": "c1r1",
          "resp": {
            "type": "userContactLink",
            "contactLink": {
              "userContactLinkId": 1,
              "connLinkContact": {
                "connFullLink": "simplex:/contact#/?v=2-7&smp=smp%3A%2F%2FTESTv7fixtureKeyHash0000000000000000000000000%3D40smp5.simplex.im%2FTESTv7fixtureQueueId00000000000000%23%2F%3Fv%3D1-4%26dh%3DTESTv7fixtureDhKey000000000000000000000000000%253D%26q%3Dc%26srv%3Dtestv7fixtureonionserver000000000000000000000000000000000.onion",
                "connShortLink": "https://smp5.simplex.im/a#TESTv7fixtureShortLinkHash00000000000000000"
              },
              "shortLinkDataSet": true,
              "shortLinkLargeDataSet": true,
              "addressSettings": {
                "businessAddress": false
              }
            }
          }
        }
            """;

    // chatItemUpdated live-edit finalize — v7.0.0-captured (2026-08-15,
    // loopback probe): the recipient-side async echo of the bot's live=off
    // terminal edit (harness body path per LiveSimpleXClient).
    private static final String V7_EDIT_FINALIZE = """
{
          "resp": {
            "type": "chatItemUpdated",
            "chatItem": {
              "chatInfo": {
                "type": "direct",
                "contact": {
                  "contactId": 3,
                  "localDisplayName": "v7probe-bot",
                  "profile": {
                    "profileId": 3,
                    "displayName": "v7probe-bot",
                    "fullName": "",
                    "peerType": "bot",
                    "localAlias": ""
                  },
                  "createdAt": "2026-08-15T15:30:47.92488636Z",
                  "updatedAt": "2026-08-15T15:30:47.92488636Z"
                }
              },
              "chatItem": {
                "chatDir": {
                  "type": "directRcv"
                },
                "meta": {
                  "itemId": 9,
                  "itemTs": "2026-08-15T15:30:49Z",
                  "itemText": "v7 probe edit final",
                  "itemStatus": {
                    "type": "rcvNew"
                  },
                  "itemSharedMsgId": "WmJvek5VcXFsNXdPMXZrZA==",
                  "itemEdited": true,
                  "userMention": false,
                  "hasLink": false,
                  "deletable": false,
                  "editable": false,
                  "showGroupAsSender": false,
                  "createdAt": "2026-08-15T15:30:49.157691134Z",
                  "updatedAt": "2026-08-15T15:30:49.157691134Z"
                },
                "content": {
                  "type": "rcvMsgContent",
                  "msgContent": {
                    "type": "text",
                    "text": "v7 probe edit final"
                  }
                },
                "mentions": {},
                "reactions": []
              }
            }
          }
        }
            """;

    // chatCmdError captured from the v7.0.0 binary (2026-08-15, loopback
    // probe): the corrId response to a grammar-violating /_send payload
    // (bare object instead of the composed array).
    private static final String V7_CHAT_CMD_ERROR = """
{
          "corrId": "c13",
          "resp": {
            "type": "chatCmdError",
            "chatError": {
              "type": "error",
              "errorType": {
                "type": "commandError",
                "message": "Failed reading: empty"
              }
            }
          }
        }
            """;

    @Test
    void v7CapturedDirectDmDecodesToInbound() {
        // M1-839 reproduction (ticket item 1): the v7.0.0 direct-DM frame
        // decodes to Inbound via the same field locations the v6.5.4.1
        // captures pinned (design 6.4.4).
        var inbound = assertInstanceOf(SimpleXMessageCodec.Inbound.class,
                SimpleXMessageCodec.decode(V7_DIRECT_DM),
                "the v7.0.0 direct inbound must decode as Inbound");
        assertEquals("3", inbound.message().sender().contactId(),
                "identity is the numeric connection contactId (D10)");
        assertEquals("v7probe-user", inbound.message().sender().displayName(),
                "display name is contact.localDisplayName");
        assertEquals("v7 probe dm canary", inbound.message().text());
        assertEquals("8", inbound.message().adapterMessageId(),
                "adapterMessageId is chatItem.meta.itemId");
        assertEquals(new ScopeRef.Dm("3"), inbound.message().scope());
    }

    @Test
    void v7CapturedGroupMentionDecodesToGroupCandidate() {
        // D51 on v7.0.0: the mention memberId objects in mentions{} and the
        // bot's own chatInfo.groupInfo.membership.memberId arrive byte-equal;
        // recognition (handler-side) is the byte-equality of the two.
        var candidate = assertInstanceOf(SimpleXMessageCodec.GroupCandidate.class,
                SimpleXMessageCodec.decode(V7_GROUP_MENTION),
                "the v7.0.0 group mention frame must decode as GroupCandidate");
        assertEquals("1", candidate.adapterGroupId());
        assertEquals("3", candidate.senderContactId(),
                "sender identity is chatDir.groupMember.memberContactId (D10)");
        assertEquals("v7probe-user", candidate.senderDisplayName());
        assertEquals("bVJpU2x2Q2ozMmZkVE81WA==", candidate.botMemberId(),
                "the D51 anchor is chatInfo.groupInfo.membership.memberId");
        assertTrue(candidate.mentionMemberIds().contains("bVJpU2x2Q2ozMmZkVE81WA=="),
                "mentions{} carries the bot's memberId object for recognition");
        assertEquals("v7 probe formatted mention canary @v7probe-bot tail", candidate.text());
        assertEquals(1, candidate.mentionSpans().size(),
                "one mention segment in formattedText");
        SimpleXMessageCodec.MentionSpan span = candidate.mentionSpans().get(0);
        assertEquals(BOT_MEMBER_ID, span.memberId());
        assertEquals("@v7probe-bot",
                candidate.text().substring(span.start(), span.start() + span.length()),
                "the span covers the bot's own @mention segment for stripping");
        assertEquals(34, span.start());
        assertEquals(12, span.length());
        assertEquals("25", candidate.adapterMessageId());
    }

    @Test
    void v7CapturedSendAckDecodesWithCorrId() {
        var ack = assertInstanceOf(SimpleXMessageCodec.SendAck.class,
                SimpleXMessageCodec.decode(V7_SEND_ACK),
                "a v7.0.0 send result with corrId decodes as SendAck");
        assertEquals("c6", ack.corrId());
        assertEquals("9", ack.chatItemId(),
                "chat-item id is chatItems[0].chatItem.meta.itemId");
    }

    @Test
    void v7CapturedGroupInvitationAndJoinPairDecodes() {
        // D52 surfaces: the invitation decodes with the validated group id
        // and the inviter's contact id; the join pair falls through Ignored.
        var invitation = assertInstanceOf(SimpleXMessageCodec.ReceivedGroupInvitation.class,
                SimpleXMessageCodec.decode(V7_GROUP_INVITATION),
                "the v7.0.0 receivedGroupInvitation must decode");
        assertEquals("1", invitation.adapterGroupId());
        assertEquals("3", invitation.inviterContactId(),
                "inviter is membership.invitedBy.byContactId with type contact");
        assertEquals(new SimpleXMessageCodec.Ignored("unknown-resp-type"),
                SimpleXMessageCodec.decode(V7_JOIN_RESPONSE));
        assertEquals(new SimpleXMessageCodec.Ignored("unknown-resp-type"),
                SimpleXMessageCodec.decode(V7_JOINED_ASYNC));
    }

    @Test
    void v7CapturedShowAddressDecodesToContactAddress() throws Exception {
        var address = assertInstanceOf(SimpleXMessageCodec.ContactAddress.class,
                SimpleXMessageCodec.decode(V7_USER_CONTACT_LINK),
                "the v7.0.0 userContactLink response must decode");
        assertEquals("c1r1", address.corrId());
        assertEquals("https://smp5.simplex.im/a#TESTv7fixtureShortLinkHash00000000000000000", address.contactLink(),
                "the short link is preferred over the full link");
    }

    @Test
    void v7CapturedEditFinalizeFrameIsIgnoredWithHarnessBodyPath() throws Exception {
        // The codec has no case for item edits (the bot never consumes
        // them): Ignored; the harness finalize body path is unchanged on v7.
        assertEquals(new SimpleXMessageCodec.Ignored("unknown-resp-type"),
                SimpleXMessageCodec.decode(V7_EDIT_FINALIZE));
        JsonNode resp = MAPPER.readTree(V7_EDIT_FINALIZE).path("resp");
        assertEquals("chatItemUpdated", resp.path("type").asText());
        assertEquals("v7 probe edit final", resp.path("chatItem").path("chatItem")
                .path("content").path("msgContent").path("text").asText(),
                "the live=off finalize body path is unchanged on v7.0.0");
    }

    @Test
    void v7CapturedErrorFrameClassifiesPermanentWithoutFreeFormLeak() {
        // FAILURE-MODE (ticket item 3): a v7.0.0 chatCmdError fails closed
        // PERMANENT; the free-form message never surfaces (security.md).
        var error = assertInstanceOf(SimpleXMessageCodec.CommandError.class,
                SimpleXMessageCodec.decode(V7_CHAT_CMD_ERROR),
                "the v7.0.0 chatCmdError must decode as CommandError");
        assertEquals("c13", error.corrId());
        assertEquals(FailureCategory.PERMANENT, error.category(),
                "unknown/unmapped error tags fail closed PERMANENT");
        assertFalse(error.detail().contains("Failed reading"),
                "the free-form message must never surface in the detail");
        assertFalse(error.detail().contains("empty"),
                "no fragment of the free-form message surfaces in the detail");
    }
}
