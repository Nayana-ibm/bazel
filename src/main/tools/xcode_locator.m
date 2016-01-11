// Copyright 2015 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Application that finds all Xcodes installed on a given Mac and will return a path
// for a given version number.
// If you have 7.0, 6.4.1 and 6.3 installed the inputs will map to:
// 7,7.0,7.0.0 = 7.0
// 6,6.4,6.4.1 = 6.4.1
// 6.3,6.3.0 = 6.3

#if !defined(__has_feature) || !__has_feature(objc_arc)
#error "This file requires ARC support."
#endif

#import <CoreServices/CoreServices.h>
#import <Foundation/Foundation.h>

// Simple data structure for tracking a version of Xcode (i.e. 6.4) with an URL to the
// appplication.
@interface XcodeVersionEntry : NSObject
@property(readonly) NSString *version;
@property(readonly) NSURL *url;
@end

@implementation XcodeVersionEntry

- (id)initWithVersion:(NSString *)version url:(NSURL *)url {
  if ((self = [super init])) {
    _version = version;
    _url = url;
  }
  return self;
}

- (id)description {
  return [NSString stringWithFormat:@"<%@ %p>: %@ %@", [self class], self, _version, _url];
}

@end

// Given an entry, insert it into a dictionary that is keyed by versions.
// For an entry that is 6.4.1:/Applications/Xcode.app
// Add it for 6.4.1, and optionally add it for 6.4 if it is newer than any entry that may already
// be there, and add it for 6 if it is newer than what is there.
static void AddEntryToDictionary(XcodeVersionEntry *entry, NSMutableDictionary *dict) {
  NSString *entryVersion = entry.version;
  NSString *subversion = entryVersion;
  dict[entryVersion] = entry;
  while (YES) {
    NSRange range = [subversion rangeOfString:@"." options:NSBackwardsSearch];
    if (range.length == 0 || range.location == 0) {
      break;
    }
    subversion = [subversion substringToIndex:range.location];
    XcodeVersionEntry *subversionEntry = dict[subversion];
    if (subversionEntry) {
      if ([subversionEntry.version compare:entry.version] == NSOrderedAscending) {
        dict[subversion] = entry;
      }
    } else {
      dict[subversion] = entry;
    }
  }
}

// Given a "version", expand it to at least 3 components by adding .0 as necessary.
static NSString *ExpandVersion(NSString *version) {
  NSArray *components = [version componentsSeparatedByString:@"."];
  NSString *appendage = nil;
  if (components.count == 2) {
    appendage = @".0";
  } else if (components.count == 1) {
    appendage = @".0.0";
  }
  if (appendage) {
    version = [version stringByAppendingString:appendage];
  }
  return version;
}

int main(int argc, const char * argv[]) {
  @autoreleasepool {
    NSString *version = nil;
    if (argc == 1) {
      version = @"";
    } else if (argc == 2) {
      version = [NSString stringWithUTF8String:argv[1]];
      NSCharacterSet *versSet = [NSCharacterSet characterSetWithCharactersInString:@"0123456789."];
      if ([version rangeOfCharacterFromSet:versSet.invertedSet].length != 0) {
        version = nil;
      }
    }
    if (version == nil) {
      printf("xcode_locator <version_number>\n"
             "Given a version number, or partial version number in x.y.z format, will attempt "
             "to return the path to the appropriate Xcode.app.\nOmitting a version number will "
             "list all available versions in JSON format.\n");
      return 1;
    }

    NSMutableDictionary *dict = [[NSMutableDictionary alloc] init];
    CFErrorRef cfError;
    NSArray *array = CFBridgingRelease(LSCopyApplicationURLsForBundleIdentifier(
        CFSTR("com.apple.dt.Xcode"), &cfError));
    if (array == nil) {
      NSError *nsError = (__bridge NSError *)cfError;
      printf("error: %s\n", nsError.description.UTF8String);
      return 1;
    }
    for (NSURL *url in array) {
      NSBundle *bundle = [NSBundle bundleWithURL:url];
      if (!bundle) {
        printf("error: Unable to open bundle at URL: %s\n", url.description.UTF8String);
        return 1;
      }
      NSString *version = bundle.infoDictionary[@"CFBundleShortVersionString"];
      if (!version) {
        printf("error: Unable to extract CFBundleShortVersionString from URL: %s\n",
               url.description.UTF8String);
        return 1;
      }
      version = ExpandVersion(version);
      XcodeVersionEntry *entry = [[XcodeVersionEntry alloc] initWithVersion:version url:url];
      AddEntryToDictionary(entry, dict);
    }

    XcodeVersionEntry *entry = [dict objectForKey:version];
    if (entry) {
      printf("%s\n", entry.url.fileSystemRepresentation);
      return 0;
    }

    // Print out list in json format.
    printf("{\n");
    for (NSString *version in dict) {
      XcodeVersionEntry *entry = dict[version];
      printf("\t\"%s\": \"%s\",\n", version.UTF8String, entry.url.fileSystemRepresentation);
    }
    printf("}\n");
    return (version == nil ? 0 : 1);
  }
}