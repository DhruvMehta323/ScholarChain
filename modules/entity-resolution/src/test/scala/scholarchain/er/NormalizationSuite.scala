package scholarchain.er

class NormalizationSuite extends munit.FunSuite:
  private def p(s: String) = Names.parse(s).getOrElse(fail(s"no parse for '$s'"))

  test("NFKC: fullwidth letters compose to ASCII before parsing") {
    assertEquals(Strings.nfkc("Ｊｏｈｎ"), "John")
    val n = p("Ｊｏｈｎ Ｓｍｉｔｈ")
    assertEquals(n.family, "smith")
    assertEquals(n.givens, List("john"))
  }

  test("NFC and NFD spellings of a name are the same name") {
    val nfc = "Müller, Hans"
    val nfd = "Müller, Hans"
    assertNotEquals(nfc, nfd)
    assertEquals(p(nfc), p(nfd))
    assertEquals(p(nfc).family, "muller")
  }

  test("foldAccents folds the documented non-decomposing letters") {
    assertEquals(Strings.foldAccents("Straße"), "strasse")
    assertEquals(Strings.foldAccents("Łukasz"), "lukasz")
    assertEquals(Strings.foldAccents("Øst"), "ost")
    assertEquals(Strings.foldAccents("Þór"), "thor")
    assertEquals(Strings.foldAccents("Renée"), "renee")
  }

  test("'Last, First' and 'First Last' parse to the same name") {
    val a = p("Smith, John Q.")
    val b = p("John Q. Smith")
    assertEquals(a.family, "smith")
    assertEquals(a.givens, List("john", "q"))
    assertEquals(a, b)
    assertEquals(a.initials, "jq")
  }

  test("initials with periods and a hyphenated initial pair") {
    val a = p("J. R. Tolkien")
    assertEquals((a.family, a.givens), ("tolkien", List("j", "r")))
    val b = p("J.-P. Sartre")
    assertEquals((b.family, b.givens), ("sartre", List("j", "p")))
    assertEquals(b.initials, "jp")
  }

  test("a surname particle stays with the family name") {
    val a = p("Ludwig van Beethoven")
    assertEquals(a.family, "van beethoven")
    assertEquals(a.familyKey, "vanbeethoven")
    assertEquals(a.givens, List("ludwig"))
  }

  test("homonym suffix is kept as identity evidence and left out of the comparison strings") {
    val a = p("Wei Wang 0001")
    assertEquals(a.suffix, Some("0001"))
    assertEquals((a.family, a.givens), ("wang", List("wei")))
    assertEquals(p("Wei Wang").suffix, None)
    // the suffix never reaches a block key
    assertEquals(Blocking.researcherPrimary("Wei Wang 0001"), Some("r1|wang|w"))
  }

  test("homonym suffix features: present on either side, conflict only when both differ") {
    def feats(a: String, b: String) =
      Features.researcher(
        ErFx.person("mention:a", scholarchain.core.SourceType.Dblp, a),
        ErFx.person("mention:b", scholarchain.core.SourceType.Crossref, b),
      )
    val none = feats("Wei Wang", "Wei Wang")
    assertEquals((none("homonym_suffix_present"), none("homonym_suffix_conflict")), (0.0, 0.0))
    val one = feats("Wei Wang 0001", "Wei Wang")
    assertEquals((one("homonym_suffix_present"), one("homonym_suffix_conflict")), (1.0, 0.0))
    val same = feats("Wei Wang 0001", "Wei Wang 0001")
    assertEquals((same("homonym_suffix_present"), same("homonym_suffix_conflict")), (1.0, 0.0))
    val diff = feats("Wei Wang 0001", "Wei Wang 0002")
    assertEquals((diff("homonym_suffix_present"), diff("homonym_suffix_conflict")), (1.0, 1.0))
    // the comparison itself ignores the suffix: names are identical
    assertEquals(diff("name_sim"), 1.0)
  }

  test("confusables are NOT folded: a Cyrillic i inside a Latin family name stays different") {
    val latin = p("John Smith")
    val spoof = p("John Smіth") // U+0456 CYRILLIC SMALL LETTER BYELORUSSIAN-UKRAINIAN I
    assertNotEquals(spoof.family, latin.family)
    assert(spoof.mixedScript, "a token mixing Latin and Cyrillic must be flagged")
    assert(!latin.mixedScript)
  }

  test("mixed-script detection: a wholly Cyrillic or wholly Greek name is not mixed") {
    assert(!Names.hasMixedScript("Иван Петров"))
    assert(!Names.hasMixedScript("Αλέξανδρος"))
    assert(Names.hasMixedScript("Аnn Smith")) // Cyrillic A + Latin nn in one token
    assert(!Names.hasMixedScript("Ann Иван")) // mixing across tokens is fine
  }

  test("a name with no letters or digits does not parse") {
    assertEquals(Names.parse(""), None)
    assertEquals(Names.parse("   "), None)
    assertEquals(Names.parse("---"), None)
    assertEquals(Names.parse(", ,"), None)
  }

  test("a single token is a family name with no given name; its block key uses '_'") {
    val a = p("Plato")
    assertEquals((a.family, a.givens, a.firstInitial), ("plato", Nil, None))
    assertEquals(Blocking.researcherPrimary("Plato"), Some("r1|plato|_"))
  }

  test("titleNorm strips markup, decodes entities, lowercases and drops punctuation") {
    assertEquals(
      Strings.titleNorm("<i>Deep</i> Learning &amp; Graphs: A   Survey!"),
      "deep learning graphs a survey",
    )
  }

  test("textNorm folds accents, drops apostrophes and splits on punctuation") {
    assertEquals(Strings.textNorm("O'Brien-Smith, Köln"), "obrien smith koln")
  }
