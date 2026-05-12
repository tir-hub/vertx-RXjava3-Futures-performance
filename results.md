# Benchmark Results

## Environment

- Machine: ThinkPad X1 Carbon 7th gen (laptop, background apps closed)
- JVM: Java 21
- Vert.x: 4.5.10
- server-2 heap: -Xms128m -Xmx256m (fixed)
- server-2 instances: 1 (single event loop thread)
- server-3 instances: availableProcessors() (not the variable under test)
- Load: 4 verticles × concurrency in-flight (closed-loop)

---

## No-delay (raw framework overhead)

`mvn exec:exec -Pbackend` (no delay)  
`mvn exec:java -Pload` (4 × 10 = 40 in-flight)

### RxJava3 sequential

```
  rps=6326     | µs: avg=6193    p50=2369    p95=17977   p99=37469  | errors=0  ← warmup
  rps=18260    | µs: avg=2189    p50=2138    p95=2659    p99=3330   | errors=0  ← JIT settling
  rps=15506    | µs: avg=2578    p50=2416    p95=3437    p99=3763   | errors=0
  rps=12663    | µs: avg=3157    p50=3089    p95=3647    p99=4670   | errors=0
  rps=12600    | µs: avg=3173    p50=3107    p95=3711    p99=4343   | errors=0
  rps=13141    | µs: avg=3042    p50=3013    p95=3570    p99=3939   | errors=0
  rps=13018    | µs: avg=3072    p50=3051    p95=3636    p99=3906   | errors=0
  rps=13046    | µs: avg=3065    p50=3054    p95=3619    p99=4001   | errors=0
  rps=13029    | µs: avg=3068    p50=3057    p95=3588    p99=3899   | errors=0
  rps=13080    | µs: avg=3057    p50=3062    p95=3563    p99=3878   | errors=0
  rps=13226    | µs: avg=3023    p50=2995    p95=3510    p99=3861   | errors=0
  rps=13095    | µs: avg=3059    p50=3024    p95=3587    p99=3950   | errors=0
  rps=12998    | µs: avg=3070    p50=3051    p95=3587    p99=3908   | errors=0
  rps=12884    | µs: avg=3104    p50=3030    p95=3608    p99=4507   | errors=0
  rps=13018    | µs: avg=3072    p50=3052    p95=3592    p99=3993   | errors=0
  rps=12925    | µs: avg=3094    p50=3103    p95=3599    p99=3968   | errors=0
  rps=13216    | µs: avg=3024    p50=2984    p95=3566    p99=3970   | errors=0
  rps=13168    | µs: avg=3037    p50=3039    p95=3588    p99=3941   | errors=0
  rps=11738    | µs: avg=3406    p50=3321    p95=4370    p99=4890   | errors=0  ← GC floor begins
  rps=10671    | µs: avg=3748    p50=3675    p95=4377    p99=4796   | errors=0
  rps=10758    | µs: avg=3717    p50=3638    p95=4319    p99=5752   | errors=0
  rps=10961    | µs: avg=3648    p50=3608    p95=4250    p99=4632   | errors=0
  rps=10928    | µs: avg=3660    p50=3546    p95=4321    p99=5497   | errors=0
  rps=10754    | µs: avg=3717    p50=3696    p95=4412    p99=4812   | errors=0
  rps=10454    | µs: avg=3825    p50=3804    p95=4520    p99=4910   | errors=0
  rps=10718    | µs: avg=3731    p50=3727    p95=4410    p99=4713   | errors=0
  rps=10745    | µs: avg=3722    p50=3718    p95=4403    p99=4819   | errors=0
  rps=10894    | µs: avg=3671    p50=3656    p95=4349    p99=4798   | errors=0
```

Peak phase (rows 4–18): ~13,000 rps, p50 ~3.0ms, p99 ~3.9ms  
Floor phase (rows 19+): ~10,700 rps, p50 ~3.7ms, p99 ~4.8ms

### Futures sequential

```
  rps=5735     | µs: avg=6833    p50=2955    p95=19497   p99=39240  | errors=0  ← warmup
  rps=17354    | µs: avg=2306    p50=2152    p95=3238    p99=3939   | errors=0  ← JIT settling
  rps=12240    | µs: avg=3262    p50=3159    p95=3957    p99=4631   | errors=0
  rps=12408    | µs: avg=3221    p50=3162    p95=3897    p99=4555   | errors=0
  rps=12563    | µs: avg=3182    p50=3064    p95=3758    p99=6702   | errors=0
  rps=12863    | µs: avg=3109    p50=3045    p95=3651    p99=3967   | errors=0
  rps=12636    | µs: avg=3165    p50=3090    p95=3696    p99=4195   | errors=0
  rps=12682    | µs: avg=3151    p50=3093    p95=3664    p99=4278   | errors=0
  rps=12658    | µs: avg=3160    p50=3133    p95=3736    p99=4094   | errors=0
  rps=12452    | µs: avg=3211    p50=3126    p95=3992    p99=4659   | errors=0
  rps=12667    | µs: avg=3158    p50=3125    p95=3799    p99=4250   | errors=0
  rps=12822    | µs: avg=3118    p50=3100    p95=3639    p99=3932   | errors=0
  rps=12704    | µs: avg=3148    p50=3089    p95=3647    p99=4163   | errors=0
  rps=10749    | µs: avg=3720    p50=3674    p95=4342    p99=4987   | errors=0  ← GC floor begins
  rps=10563    | µs: avg=3785    p50=3667    p95=4445    p99=5524   | errors=0
  rps=10674    | µs: avg=3745    p50=3665    p95=4395    p99=6984   | errors=0
  rps=10601    | µs: avg=3772    p50=3718    p95=4411    p99=4981   | errors=0
  rps=10614    | µs: avg=3768    p50=3676    p95=4488    p99=5146   | errors=0
  rps=10929    | µs: avg=3660    p50=3552    p95=4368    p99=5231   | errors=0
  rps=10577    | µs: avg=3779    p50=3712    p95=4481    p99=5368   | errors=0
  rps=10645    | µs: avg=3756    p50=3689    p95=4391    p99=4759   | errors=0
  rps=10734    | µs: avg=3726    p50=3701    p95=4450    p99=4728   | errors=0
  rps=10526    | µs: avg=3800    p50=3687    p95=4551    p99=6728   | errors=0
  rps=10591    | µs: avg=3774    p50=3639    p95=4465    p99=7537   | errors=0
  rps=10587    | µs: avg=3777    p50=3756    p95=4422    p99=4776   | errors=0
  rps=10725    | µs: avg=3729    p50=3709    p95=4393    p99=4725   | errors=0
  rps=10875    | µs: avg=3678    p50=3700    p95=4377    p99=4655   | errors=0
  rps=10638    | µs: avg=3757    p50=3647    p95=4458    p99=7140   | errors=0
```

Peak phase (rows 3–13): ~12,700 rps, p50 ~3.1ms, p99 ~4.2ms  
Floor phase (rows 14+): ~10,700 rps, p50 ~3.7ms, p99 ~5.2ms

---

## 250ms backend delay — sequential

`mvn exec:exec -Pbackend -Dbackend.delayMs=250`  
`mvn exec:java -Pload -Dload.concurrency=100` (4 × 100 = 400 in-flight)  
Expected ceiling: 400 / 0.750s ≈ 533 rps

### RxJava3 sequential

```
  rps=347      | µs: avg=1018824 p50=768260  p95=1794942 p99=1807715 | errors=0  ← warmup
  rps=534      | µs: avg=753420  p50=753196  p95=755418  p99=757463  | errors=0
  rps=559      | µs: avg=753971  p50=753810  p95=755815  p99=757561  | errors=0
  rps=487      | µs: avg=753990  p50=753688  p95=755543  p99=757009  | errors=0
  rps=553      | µs: avg=753941  p50=753797  p95=755621  p99=758764  | errors=0
  rps=534      | µs: avg=753714  p50=753567  p95=755488  p99=758637  | errors=0
  rps=507      | µs: avg=754153  p50=754079  p95=755978  p99=757447  | errors=0
  rps=559      | µs: avg=754111  p50=754039  p95=755875  p99=756712  | errors=0
  rps=516      | µs: avg=754209  p50=754044  p95=756306  p99=757305  | errors=0
  rps=525      | µs: avg=754394  p50=754230  p95=756544  p99=757726  | errors=0
  rps=559      | µs: avg=754954  p50=754794  p95=757326  p99=758864  | errors=0
  rps=502      | µs: avg=754736  p50=754585  p95=756629  p99=761022  | errors=0
  rps=539      | µs: avg=754937  p50=754781  p95=757026  p99=758702  | errors=0
  rps=542      | µs: avg=754518  p50=754355  p95=756309  p99=760262  | errors=0
  rps=498      | µs: avg=754646  p50=754535  p95=756729  p99=758251  | errors=0
  rps=559      | µs: avg=754511  p50=754269  p95=756215  p99=762696  | errors=0
  rps=510      | µs: avg=754429  p50=754243  p95=756532  p99=758237  | errors=0
  rps=531      | µs: avg=754325  p50=754060  p95=756836  p99=760077  | errors=0
  rps=559      | µs: avg=754524  p50=754303  p95=756876  p99=759022  | errors=0
  rps=502      | µs: avg=754333  p50=754139  p95=756194  p99=760178  | errors=0
  rps=539      | µs: avg=754193  p50=754116  p95=755680  p99=756406  | errors=0
  rps=554      | µs: avg=754096  p50=754029  p95=755596  p99=756260  | errors=0
```

Stable: ~530 rps, p50 ~754ms, p99 max 762ms — perfectly flat, no degradation

### Futures sequential

```
  rps=400      | µs: avg=919080  p50=758031  p95=1532784 p99=1574634 | errors=0  ← warmup
  rps=515      | µs: avg=753468  p50=753291  p95=755372  p99=756451  | errors=0
  rps=525      | µs: avg=753596  p50=753407  p95=755784  p99=757131  | errors=0
  rps=560      | µs: avg=753742  p50=753623  p95=755556  p99=756650  | errors=0
  rps=487      | µs: avg=754215  p50=754031  p95=756036  p99=762710  | errors=0
  rps=553      | µs: avg=754926  p50=754354  p95=758890  p99=771992  | errors=0
  rps=560      | µs: avg=754574  p50=754425  p95=756760  p99=758522  | errors=0
  rps=483      | µs: avg=754640  p50=754637  p95=756027  p99=756674  | errors=0
  rps=557      | µs: avg=754492  p50=754156  p95=757149  p99=763258  | errors=0
  rps=525      | µs: avg=754037  p50=753888  p95=755605  p99=763959  | errors=0
  rps=515      | µs: avg=754077  p50=753988  p95=755724  p99=756410  | errors=0
  rps=560      | µs: avg=754331  p50=754296  p95=756095  p99=756985  | errors=0
  rps=487      | µs: avg=756285  p50=755240  p95=762519  p99=765791  | errors=0
  rps=553      | µs: avg=753594  p50=753446  p95=755326  p99=759125  | errors=0
  rps=560      | µs: avg=753955  p50=753884  p95=755659  p99=757076  | errors=0
  rps=485      | µs: avg=754037  p50=753776  p95=757129  p99=760534  | errors=0
  rps=555      | µs: avg=753133  p50=753035  p95=754876  p99=755670  | errors=0
  rps=560      | µs: avg=754120  p50=753974  p95=756748  p99=758905  | errors=0
  rps=480      | µs: avg=754170  p50=753987  p95=757419  p99=760377  | errors=0
  rps=560      | µs: avg=754736  p50=754098  p95=760075  p99=762836  | errors=0
  rps=487      | µs: avg=753686  p50=753635  p95=755302  p99=755954  | errors=0
  rps=553      | µs: avg=754582  p50=753909  p95=760595  p99=765642  | errors=0
  rps=560      | µs: avg=755329  p50=754876  p95=759153  p99=761432  | errors=0
  rps=485      | µs: avg=754369  p50=754211  p95=756592  p99=758338  | errors=0
  rps=555      | µs: avg=754266  p50=754184  p95=756503  p99=757681  | errors=0
```

Stable: ~530 rps, p50 ~754ms, p99 max 771ms — perfectly flat, no degradation

---

## 250ms backend delay — parallel

`mvn exec:exec -Pbackend -Dbackend.delayMs=250`  
`mvn exec:java -Pload -Dload.concurrency=100` (4 × 100 = 400 in-flight)  
Expected ceiling: 400 / 0.250s ≈ 1,600 rps

### Parallel RxJava3 (Single.zip)

```
  rps=1233     | µs: avg=308097  p50=251629  p95=933616  p99=1094118 | errors=0  ← warmup
  rps=1562     | µs: avg=253505  p50=251424  p95=266537  p99=287690  | errors=0
  rps=1592     | µs: avg=254392  p50=251604  p95=274345  p99=297950  | errors=0
  rps=1561     | µs: avg=253654  p50=251572  p95=265768  p99=288352  | errors=0
  rps=1585     | µs: avg=253922  p50=252033  p95=264842  p99=273311  | errors=0
  rps=1596     | µs: avg=252951  p50=251918  p95=259456  p99=266616  | errors=0
  rps=1580     | µs: avg=251944  p50=251650  p95=254484  p99=256808  | errors=0
  rps=1573     | µs: avg=252074  p50=251516  p95=255858  p99=261836  | errors=0
  rps=1578     | µs: avg=254396  p50=251890  p95=269032  p99=285874  | errors=0
  rps=1564     | µs: avg=255443  p50=252153  p95=273990  p99=287759  | errors=0
  rps=1568     | µs: avg=254512  p50=251941  p95=269800  p99=285462  | errors=0
  rps=1598     | µs: avg=255676  p50=252110  p95=277360  p99=291789  | errors=0
  rps=1530     | µs: avg=254217  p50=252709  p95=262995  p99=273139  | errors=0
  rps=1599     | µs: avg=255605  p50=252328  p95=278870  p99=284859  | errors=0
  rps=1534     | µs: avg=254256  p50=252068  p95=264180  p99=294193  | errors=0
  rps=1600     | µs: avg=254691  p50=252131  p95=270092  p99=280584  | errors=0
  rps=1562     | µs: avg=254305  p50=252022  p95=269047  p99=292597  | errors=0
```

Stable: ~1,580 rps, p50 ~252ms, p99 max 298ms — no degradation

### Parallel Futures (CompositeFuture.all)

```
  rps=1235     | µs: avg=307911  p50=251811  p95=964972  p99=1125477 | errors=0  ← warmup
  rps=1564     | µs: avg=255987  p50=251849  p95=287681  p99=309145  | errors=0
  rps=1587     | µs: avg=252915  p50=251860  p95=259719  p99=268144  | errors=0
  rps=1573     | µs: avg=252153  p50=251681  p95=255245  p99=258161  | errors=0
  rps=1589     | µs: avg=251759  p50=251402  p95=254155  p99=257387  | errors=0
  rps=1581     | µs: avg=252276  p50=251621  p95=256297  p99=260016  | errors=0
  rps=1596     | µs: avg=252793  p50=251774  p95=259440  p99=266439  | errors=0
  rps=1600     | µs: avg=252476  p50=251643  p95=258563  p99=262668  | errors=0
  rps=1565     | µs: avg=254262  p50=252087  p95=268069  p99=291946  | errors=0
  rps=1549     | µs: avg=254936  p50=251965  p95=272172  p99=287498  | errors=0
  rps=1587     | µs: avg=251951  p50=251411  p95=255841  p99=262806  | errors=0
  rps=1568     | µs: avg=255090  p50=251871  p95=279913  p99=301645  | errors=0
  rps=1586     | µs: avg=252868  p50=251813  p95=259106  p99=263553  | errors=0
  rps=1593     | µs: avg=252223  p50=251737  p95=255521  p99=261490  | errors=0
  rps=1600     | µs: avg=252340  p50=251633  p95=257002  p99=262985  | errors=0
  rps=1578     | µs: avg=251809  p50=251449  p95=254276  p99=258228  | errors=0
```

Stable: ~1,580 rps, p50 ~252ms, p99 max 309ms — no degradation

---

## Summary

| Variant | Stable rps | p50 ms | p99 max ms | Degrades? |
|---|---|---|---|---|
| Seq Rx       |   ~530 | 754 |   762 | no (delay test) |
| Seq Futures  |   ~530 | 754 |   771 | no (delay test) |
| Par Rx       | ~1,580 | 252 |   298 | no |
| Par Futures  | ~1,580 | 252 |   309 | no |
| Seq Rx (no delay)      | ~13,000 peak / ~10,700 floor | 3.0 | 3.9 | yes (GC, 256m heap) |
| Seq Futures (no delay) | ~12,700 peak / ~10,700 floor | 3.1 | 4.2 | yes (GC, 256m heap) |

Sequential vs parallel with 250ms delay: **3× throughput, 3× lower latency** — the dominant choice.  
Rx vs Futures: throughput identical; Rx p99 marginally tighter in all scenarios.
