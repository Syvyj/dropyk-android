#!/usr/bin/env python3
"""Builds the "Send to Dropyk" shortcut for iPhone, iPad and Mac, in English and Ukrainian,
and signs it with `shortcuts sign` (macOS, signed in to iCloud): iOS imports signed files only.
When the shortcut is added, Shortcuts asks for the TV's address — the one under the QR code.
From the Share sheet it then POSTs every file to <address>upload?name=…, like the phone page.
Run: python3 shortcut/make.py  →  web/shortcut/Send to Dropyk.shortcut and Надіслати на Dropyk.shortcut"""
import os
import plistlib
import subprocess
import tempfile
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "web", "shortcut")

TEXT = {
    "en": dict(question="Dropyk's address on your TV — it's under the QR code, like http://192.168.0.42:8777/abc123/",
               sent="Sent to the TV", file="Send to Dropyk.shortcut"),
    "uk": dict(question="Адреса Dropyk на телевізорі — вона під QR-кодом, наприклад http://192.168.0.42:8777/abc123/",
               sent="Надіслано на телевізор", file="Надіслати на Dropyk.shortcut"),
}


def tokens(string, attachments):
    """A text field with variables in it: U+FFFC marks each one."""
    return {"Value": {"string": string, "attachmentsByRange": {f"{{{i}, 1}}": a for i, a in attachments.items()}},
            "WFSerializationType": "WFTextTokenString"}


def item(prop=None):
    v = {"Type": "Variable", "VariableName": "Repeat Item"}
    if prop:
        v["Aggrandizements"] = [{"Type": "WFPropertyVariableAggrandizement", "PropertyName": prop}]
    return v


def build(lang):
    t = TEXT[lang]
    address, encoded, group = (str(uuid.uuid4()).upper() for _ in range(3))
    actions = [
        # The address, asked for once when the shortcut is added.
        {"WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
         "WFWorkflowActionParameters": {"UUID": address, "WFTextActionText": "http://"}},
        {"WFWorkflowActionIdentifier": "is.workflow.actions.repeat.each",
         "WFWorkflowActionParameters": {"GroupingIdentifier": group, "WFControlFlowMode": 0,
                                        "WFInput": {"Value": {"Type": "ExtensionInput"}, "WFSerializationType": "WFTextTokenAttachment"}}},
        # "IMG_2041" + "." + "jpg", URL-encoded for the query string.
        {"WFWorkflowActionIdentifier": "is.workflow.actions.urlencode",
         "WFWorkflowActionParameters": {"UUID": encoded, "WFInput": tokens("￼.￼", {0: item("Name"), 2: item("File Extension")})}},
        {"WFWorkflowActionIdentifier": "is.workflow.actions.downloadurl",
         "WFWorkflowActionParameters": {
             "WFURL": tokens("￼upload?name=￼", {
                 0: {"Type": "ActionOutput", "OutputUUID": address, "OutputName": "Text"},
                 13: {"Type": "ActionOutput", "OutputUUID": encoded, "OutputName": "URL Encoded Text"}}),
             "WFHTTPMethod": "POST", "WFHTTPBodyType": "File", "ShowHeaders": True,
             "WFRequestVariable": {"Value": item(), "WFSerializationType": "WFTextTokenAttachment"}}},
        {"WFWorkflowActionIdentifier": "is.workflow.actions.repeat.each",
         "WFWorkflowActionParameters": {"GroupingIdentifier": group, "WFControlFlowMode": 2}},
        {"WFWorkflowActionIdentifier": "is.workflow.actions.notification",
         "WFWorkflowActionParameters": {"WFNotificationActionTitle": "Dropyk", "WFNotificationActionBody": t["sent"]}},
    ]
    return {
        "WFWorkflowClientVersion": "2607.0.2",
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowIcon": {"WFWorkflowIconStartColor": 463140863, "WFWorkflowIconGlyphNumber": 59511},
        "WFWorkflowTypes": ["ActionExtension"],
        "WFWorkflowHasShortcutInputVariables": True,
        "WFWorkflowInputContentItemClasses": ["WFImageContentItem", "WFAVAssetContentItem", "WFGenericFileContentItem",
                                              "WFPDFContentItem", "WFPhotoMediaContentItem"],
        "WFWorkflowImportQuestions": [{"ActionIndex": 0, "Category": "Parameter", "ParameterKey": "WFTextActionText",
                                       "Text": t["question"], "DefaultValue": "http://"}],
        "WFWorkflowActions": actions,
    }


os.makedirs(OUT, exist_ok=True)
for lang, t in TEXT.items():
    with tempfile.NamedTemporaryFile(suffix=".shortcut", delete=False) as f:
        plistlib.dump(build(lang), f, fmt=plistlib.FMT_BINARY)
    out = os.path.join(OUT, t["file"])
    subprocess.run(["shortcuts", "sign", "--mode", "anyone", "--input", f.name, "--output", out], check=True)
    os.chmod(out, 0o644)  # served by the site
    os.unlink(f.name)
    print("signed", os.path.relpath(out))
