/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.stubs;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.test.KotlinLightProjectDescriptor;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.stubs.KotlinClassStub;
import org.jetbrains.kotlin.psi.stubs.elements.KtStubElementTypes;
import org.junit.internal.runners.JUnit38ClassRunner;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(JUnit38ClassRunner.class)
public class KotlinStubsTest extends LightJavaCodeInsightFixtureTestCase {
    @NotNull
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return KotlinLightProjectDescriptor.INSTANCE;
    }

    public void testSuperclassNames() {
        PsiFile psiFile = myFixture.configureByText("foo.kt", "import java.util.ArrayList as alist\nclass C(): alist() { }");
        List<KtDeclaration> declarations = ((KtFile) psiFile).getDeclarations();
        KtClass ktClass = (KtClass) declarations.get(0);
        KotlinClassStub stub = KtStubElementTypes.CLASS.createStub(ktClass, null);
        List<String> names = stub.getSuperNames();
        assertSameElements(names, "ArrayList", "alist");
    }

    public void testClassIsTrait() {
        PsiFile psiFile = myFixture.configureByText("foo.kt", "interface Test { }");
        List<KtDeclaration> declarations = ((KtFile) psiFile).getDeclarations();
        KtClass ktClass = (KtClass) declarations.get(0);
        KotlinClassStub stub = KtStubElementTypes.CLASS.createStub(ktClass, null);
        assertEquals(true, stub.isInterface());
    }
}
