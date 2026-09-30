static List<String> parse(String s) {
  List<String> tokens = new ArrayList<>();
  StringBuilder cur = new StringBuilder();
  boolean inToken = false;
  boolean inSingle = false;
  boolean inDouble = false;

  for (int i = 0; i < s.length(); i++) {
    char c = s.charAt(i);
    if (inSingle) {
      if (c == '\'') inSingle = false;
      else cur.append(c);
    } else if (inDouble) {
      if (c == '"') inDouble = false;
      else cur.append(c);
    } else if (c == '\'') {
      inSingle = true;
      inToken = true;
    } else if (c == '"') {
      inDouble = true;
      inToken = true;
    } else if (Character.isWhitespace(c)) {
      if (inToken) {
        tokens.add(cur.toString());
        cur.setLength(0);
        inToken = false;
      }
    } else {
      cur.append(c);
      inToken = true;
    }
  }
  if (inToken) tokens.add(cur.toString());
  return tokens;
}