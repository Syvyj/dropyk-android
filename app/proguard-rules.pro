# protobuf-lite finds message fields by name through reflection; renamed by R8, they are
# "not found" at the first frame (seen on the TV: "Field version_ for … not found").
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
