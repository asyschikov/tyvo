# Eval stats

Frozen from the raw runs, which are not kept; rerun them with the harness.
Per model: cases scored (n), cases with only acceptable edits (clean), and counts
of overreach: DROP (a word removed), SUB (a word replaced), ADD (a word invented),
FAIL (a real correction left undone) and CASE (output starts lowercase). Calls
that failed on rate limits are left out of n. Each case was run once at
temperature 0, so differences of one or two cases are noise.

## baseline_41cases__6models

Old prompt, first 41 cases. mistral-small and mistral-medium were rate-limited (429) on every call and are excluded.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
current/ministral-8b-latest                 41    20    14   11    0    0    2
current/ministral-3b-latest                 41    10    23   12    3    1   18
current/ministral-14b-latest                41    18    17   19    2    0    0
current/open-mistral-nemo                   41    18    14   10    1    0    2
```

## baseline_49cases__4models

Old prompt, 49 cases (regression cases added).

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
current/ministral-8b-latest                 49    26    16    9    0    0    1
current/ministral-3b-latest                 49    13    24   13    2    3   18
current/ministral-14b-latest                49    23    21   18    2    0    0
current/open-mistral-nemo                   49    27    16    8    0    0    1
```

## baseline_68cases__4models

Old prompt, all 68 cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
current/ministral-8b-latest                 68    29    24   18    1    0    3
current/ministral-14b-latest                65    31    24   23    2    0    0
current/open-mistral-nemo                   68    31    25   18    0    0    3
current/ministral-3b-latest                 68    14    36   24    3    4   27
```

## v1__ministral-8b-latest+ministral-3b-latest+ministral-14b-latest+open-mistral-nemo

v1 on 49 cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
v1/ministral-8b-latest                      49    25     8    3    1    0   18
v1/ministral-3b-latest                      49    11    16    6    1    2   32
v1/ministral-14b-latest                     46    29    11    4    1    0    6
v1/open-mistral-nemo                        49    23     9    5    2    0   18
```

## v1__ministral-8b-latest+ministral-14b-latest+open-mistral-nemo+ministral-3b-latest

v1 on all 68 cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
v1/ministral-8b-latest                      68    29    13    9    1    0   27
v1/ministral-14b-latest                     65    41    14    8    1    0    7
v1/open-mistral-nemo                        68    29    13    9    1    0   27
v1/ministral-3b-latest                      68    13    23   11    1    3   46
```

## v2

v2: input -> output examples; always capitalise.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
v2/ministral-8b-latest                      68    60     4    4    0    0    1
v2/ministral-14b-latest                     65    58     6    1    0    0    0
v2/open-mistral-nemo                        68    60     5    4    0    0    1
v2/ministral-3b-latest                      68    39    15   14    1    4    5
```

## v3

v3: grammar judged by 'would they type it this way'.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
v3/ministral-8b-latest                      68    58     5    6    0    1    1
v3/ministral-14b-latest                     65    56     8    3    0    0    0
v3/open-mistral-nemo                        68    58     5    5    0    1    1
v3/ministral-3b-latest                      68    37    17   19    2    4    3
```

## v4

v4: keeps 'I mean', repetition and present tense.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
v4/ministral-8b-latest                      68    58     3    7    0    1    1
v4/ministral-14b-latest                     65    62     4    0    0    0    0
v4/open-mistral-nemo                        68    58     5    6    0    1    1
```

## shipped

v4 as ported to Kotlin.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
shipped/ministral-8b-latest                 68    59     4    6    0    0    0
shipped/ministral-14b-latest                65    61     4    0    0    0    0
shipped/open-mistral-nemo                   68    59     4    6    0    0    0
```

## lang_english

Per-language experiment: the English prompt.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_english/ministral-8b-latest            68    56     5    8    0    1    0
```

## lang_generic_english

Per-language experiment: the generic prompt on English cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_english/ministral-8b-latest    68    58     5    7    0    1    1
```

## generic_plus

generic+ (the final app prompt) on English cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
generic_plus/ministral-8b-latest            68    57     4    6    0    1    2
```

## lang_german

German prompt, original German cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_german/ministral-8b-latest             14    11     1    3    0    0    0
lang_german/ministral-14b-latest            14    11     1    2    0    0    0
```

## lang_generic_german

Generic prompt, original German cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_german/ministral-8b-latest     14    11     0    4    0    0    0
lang_generic_german/ministral-14b-latest    14    11     1    3    0    0    0
```

## lang_german_ho

German prompt, held-out German cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_german_ho/ministral-8b-latest           8     4     4    0    1    1    0
lang_german_ho/ministral-14b-latest          8     6     2    0    0    1    0
```

## lang_generic_ho_german

Generic prompt, held-out German cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_ho_german/ministral-8b-latest   8     4     3    2    0    2    0
lang_generic_ho_german/ministral-14b-latest   8     5     3    0    1    1    0
```

## generic_plus_german

generic+ (the final app prompt), all German cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
generic_plus_german/ministral-8b-latest     22    18     2    3    0    1    0
generic_plus_german/ministral-14b-latest    22    17     1    4    0    1    0
```

## lang_dutch

Dutch prompt, original Dutch cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_dutch/ministral-8b-latest              14    10     2    1    0    1    0
lang_dutch/ministral-14b-latest             11     8     1    2    1    0    0
```

## lang_generic_dutch

Generic prompt, original Dutch cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_dutch/ministral-8b-latest      14     6     2    3    1    3    1
lang_generic_dutch/ministral-14b-latest     14     8     2    4    0    1    0
```

## lang_dutch_ho

Dutch prompt, held-out Dutch cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_dutch_ho/ministral-8b-latest            8     3     3    3    0    0    0
lang_dutch_ho/ministral-14b-latest           8     5     2    0    0    1    0
```

## lang_generic_ho_dutch

Generic prompt, held-out Dutch cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_ho_dutch/ministral-8b-latest    8     4     2    2    1    1    0
lang_generic_ho_dutch/ministral-14b-latest   8     6     2    3    0    0    0
```

## generic_plus_dutch

generic+ (the final app prompt), all Dutch cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
generic_plus_dutch/ministral-8b-latest      22    14     4    8    0    2    0
generic_plus_dutch/ministral-14b-latest     20    16     1    1    0    2    0
```

## lang_russian

Russian prompt, original Russian cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_russian/ministral-8b-latest            14     9     3    1    2    1    0
lang_russian/ministral-14b-latest           14    11     1    1    1    1    0
```

## lang_generic_russian

Generic prompt, original Russian cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_russian/ministral-8b-latest    14     7     2    4    1    1    2
lang_generic_russian/ministral-14b-latest   14     6     3    4    1    2    0
```

## lang_russian_ho

Russian prompt, held-out Russian cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_russian_ho/ministral-8b-latest          8     6     2    0    0    1    0
lang_russian_ho/ministral-14b-latest         8     5     3    1    1    0    0
```

## lang_generic_ho_russian

Generic prompt, held-out Russian cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_generic_ho_russian/ministral-8b-latest   8     5     1    0    0    1    2
lang_generic_ho_russian/ministral-14b-latest   8     5     1    2    0    1    0
```

## generic_plus_russian

generic+ (the final app prompt), all Russian cases.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
generic_plus_russian/ministral-8b-latest    22    12     4    3    1    2    4
generic_plus_russian/ministral-14b-latest   22    14     4    5    1    1    0
```

## lang_dutch_r2

Dutch prompt after replacing an example the model had echoed.

```
variant/model                                n clean  DROP  SUB  ADD FAIL CASE
lang_dutch_r2/ministral-8b-latest           14    10     3    1    1    0    0
lang_dutch_r2/ministral-14b-latest          14    11     2    1    0    0    0
```

## Written (heavy) quick action

Run on ministral-8b's light output. Tone lost: a slang, swear or address word
went missing. Still spoken: "I'm like" / "he goes" left in. Length off: outside
0.6-1.3x the input.

### First instruction

```
ministral-8b-latest    n=25  tone lost 8  still spoken 0  corrections undone 0  length off 3
ministral-14b-latest   n=25  tone lost 8  still spoken 1  corrections undone 0  length off 3
open-mistral-nemo      n=25  tone lost 8  still spoken 0  corrections undone 0  length off 3
```

### Tone-preserving, with example slang

```
ministral-8b-latest    n=25  tone lost 4  still spoken 0  corrections undone 0  length off 2
ministral-14b-latest   n=25  tone lost 5  still spoken 1  corrections undone 0  length off 1
```

### Shipped: no example slang

```
ministral-8b-latest    n=25  tone lost 5  still spoken 0  corrections undone 0  length off 1
ministral-14b-latest   n=25  tone lost 4  still spoken 1  corrections undone 0  length off 2
```

